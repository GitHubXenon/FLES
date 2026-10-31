package edu.osaka_u.fles;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minimal XLSX reader for FLES.
 *
 * Supported workbook contract:
 * - .xlsx only (not legacy .xls)
 * - first worksheet only
 * - row 1 is the header row
 * - columns A-G are required by position; H (audio filename) is optional
 * - shared strings, inline strings, ordinary text and cached formula values
 * - cell formatting is ignored intentionally
 *
 * No third-party dependency is required: XLSX is a ZIP package containing XML,
 * and Android already provides XmlPullParser.
 */
public final class XlsxReader {

    private static final long MAX_TOTAL_XML_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_INPUT_BYTES = 50 * 1024 * 1024;
    private static final int MAX_DATA_ROWS = 100000;

    private static final String CONTENT_TYPES_XML = "[Content_Types].xml";
    private static final String ROOT_RELS_XML = "_rels/.rels";
    private static final String WORKBOOK_XML = "xl/workbook.xml";
    private static final String WORKBOOK_RELS_XML = "xl/_rels/workbook.xml.rels";
    private static final String SHARED_STRINGS_XML = "xl/sharedStrings.xml";

    private XlsxReader() { }

    /** Parse an XLSX byte stream and return FLES sentence rows. */
    public static List<FlesDatabase.Sentence> read(InputStream input) throws IOException {
        if (input == null) throw new IOException("Excel 输入流为空");

        Map<String, byte[]> parts = readRelevantZipParts(input);
        if (!parts.containsKey(CONTENT_TYPES_XML) || !parts.containsKey(ROOT_RELS_XML)) {
            throw new IOException("文件缺少 XLSX 必需的 Open Packaging parts");
        }
        byte[] workbookXml = parts.get(WORKBOOK_XML);
        byte[] relsXml = parts.get(WORKBOOK_RELS_XML);
        if (workbookXml == null || relsXml == null) {
            throw new IOException("文件不是有效的 XLSX 工作簿，缺少 workbook.xml 或关系文件");
        }

        String firstSheetPath = findFirstSheetPath(workbookXml, relsXml);
        byte[] sheetXml = parts.get(firstSheetPath);
        if (sheetXml == null) {
            throw new IOException("找不到第一个工作表：" + firstSheetPath);
        }

        List<String> sharedStrings = new ArrayList<String>();
        byte[] sharedXml = parts.get(SHARED_STRINGS_XML);
        if (sharedXml != null) sharedStrings = parseSharedStrings(sharedXml);

        List<FlesDatabase.Sentence> result = parseWorksheet(sheetXml, sharedStrings);
        if (result.isEmpty()) {
            throw new IOException("Excel 中没有有效例句（C 列为空）；为保护旧题库，本次没有导入");
        }
        return result;
    }

    /** Convenience overload for a byte array already read and hashed by FlesDataSync. */
    public static List<FlesDatabase.Sentence> read(byte[] bytes) throws IOException {
        if (bytes == null) throw new IOException("Excel 文件内容为空");
        if (bytes.length == 0) throw new IOException("Excel 文件是空文件");
        if (bytes.length > MAX_INPUT_BYTES) throw new IOException("Excel 文件超过 50 MB，已停止读取");
        if (!hasZipEndRecord(bytes)) {
            throw new IOException("XLSX 文件尚未完整复制或 ZIP 结构不完整，请等文件复制结束后再试");
        }
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        try {
            return read(input);
        } finally {
            try { input.close(); } catch (IOException ignored) { }
        }
    }

    // ------------------------------------------------------------------
    // ZIP package reading
    // ------------------------------------------------------------------

    private static Map<String, byte[]> readRelevantZipParts(InputStream input) throws IOException {
        Map<String, byte[]> parts = new HashMap<String, byte[]>();
        ZipInputStream zip = new ZipInputStream(input);
        long totalUncompressed = 0L;
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = normalizeZipPath(entry.getName());
                if (!entry.isDirectory() && isRelevantPart(name)) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    long entryBytes = 0L;
                    while ((count = zip.read(buffer)) != -1) {
                        entryBytes += count;
                        totalUncompressed += count;
                        if (totalUncompressed > MAX_TOTAL_XML_BYTES) {
                            throw new IOException("Excel 内部 XML 解压后超过 64 MB，已停止读取");
                        }
                        output.write(buffer, 0, count);
                    }
                    parts.put(name, output.toByteArray());
                    output.close();
                }
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw e;
        } finally {
            try { zip.close(); } catch (IOException ignored) { }
        }
        if (parts.isEmpty()) throw new IOException("无法读取 XLSX ZIP 内容；请确认文件为 .xlsx 格式");
        return parts;
    }


    /** Reject obviously incomplete USB/MTP copies that lack the ZIP end record. */
    private static boolean hasZipEndRecord(byte[] bytes) {
        if (bytes == null || bytes.length < 22) return false;
        int start = Math.max(0, bytes.length - 65557); // EOCD + maximum ZIP comment length
        for (int i = bytes.length - 22; i >= start; i--) {
            if ((bytes[i] & 0xff) == 0x50 && (bytes[i + 1] & 0xff) == 0x4b &&
                    (bytes[i + 2] & 0xff) == 0x05 && (bytes[i + 3] & 0xff) == 0x06) {
                int commentLength = (bytes[i + 20] & 0xff) | ((bytes[i + 21] & 0xff) << 8);
                return i + 22 + commentLength <= bytes.length;
            }
        }
        return false;
    }

    private static boolean isRelevantPart(String name) {
        if (CONTENT_TYPES_XML.equals(name) || ROOT_RELS_XML.equals(name) ||
                WORKBOOK_XML.equals(name) || WORKBOOK_RELS_XML.equals(name) ||
                SHARED_STRINGS_XML.equals(name)) return true;
        return name.startsWith("xl/worksheets/") && name.endsWith(".xml");
    }

    private static String normalizeZipPath(String path) {
        String value = path == null ? "" : path.replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        return value;
    }

    // ------------------------------------------------------------------
    // Workbook metadata: first sheet -> relationship target
    // ------------------------------------------------------------------

    private static String findFirstSheetPath(byte[] workbookXml, byte[] relsXml) throws IOException {
        String relationId = null;
        XmlPullParser parser = newParser(workbookXml);
        try {
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "sheet".equals(tag(parser))) {
                    relationId = findAttributeByLocalName(parser, "id");
                    if (relationId != null && relationId.length() > 0) break;
                }
                event = parser.next();
            }
        } catch (Exception e) {
            throw new IOException("无法解析 workbook.xml", e);
        }
        if (relationId == null) throw new IOException("工作簿没有可识别的第一个工作表");

        String target = null;
        parser = newParser(relsXml);
        try {
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "Relationship".equals(tag(parser))) {
                    String id = parser.getAttributeValue(null, "Id");
                    if (relationId.equals(id)) {
                        target = parser.getAttributeValue(null, "Target");
                        break;
                    }
                }
                event = parser.next();
            }
        } catch (Exception e) {
            throw new IOException("无法解析 workbook.xml.rels", e);
        }
        if (target == null || target.length() == 0) {
            throw new IOException("无法找到第一个工作表对应的文件路径");
        }
        return resolveWorkbookTarget(target);
    }

    /** 去掉命名空间前缀：Android 的解析器未开启命名空间时，名字是 "r:id"、"x:sheet" 这种带前缀的原样文本。 */
    private static String local(String name) {
        if (name == null) return "";
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static String tag(XmlPullParser parser) {
        return local(parser.getName());
    }

    private static String findAttributeByLocalName(XmlPullParser parser, String localName) {
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            if (localName.equals(local(parser.getAttributeName(i)))) {
                return parser.getAttributeValue(i);
            }
        }
        return null;
    }

    /** Excel 会把控制字符写成 _x000D_ 这样的转义，这里还原。 */
    private static String unescapeExcel(String text) {
        if (text == null || text.indexOf("_x") < 0) return text;
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (i + 7 <= text.length() && text.charAt(i) == '_' && text.charAt(i + 1) == 'x'
                    && text.charAt(i + 6) == '_') {
                try {
                    out.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                    i += 7;
                    continue;
                } catch (NumberFormatException ignored) { }
            }
            out.append(text.charAt(i));
            i++;
        }
        return out.toString();
    }

    private static String resolveWorkbookTarget(String target) {
        String path = target.replace('\\', '/');
        if (path.startsWith("/")) return normalizeZipPath(path);
        // Relationship targets are relative to xl/workbook.xml.
        String combined = "xl/" + path;
        String[] parts = combined.split("/");
        List<String> stack = new ArrayList<String>();
        for (String part : parts) {
            if (part.length() == 0 || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
            } else {
                stack.add(part);
            }
        }
        StringBuilder result = new StringBuilder();
        for (String part : stack) {
            if (result.length() > 0) result.append('/');
            result.append(part);
        }
        return result.toString();
    }

    // ------------------------------------------------------------------
    // Shared strings
    // ------------------------------------------------------------------

    private static List<String> parseSharedStrings(byte[] xml) throws IOException {
        List<String> values = new ArrayList<String>();
        XmlPullParser parser = newParser(xml);
        boolean inStringItem = false;
        int phoneticDepth = 0; // <rPh> 是日文注音(振り仮名)，不是单元格正文，必须跳过
        StringBuilder current = null;
        try {
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                String name = event == XmlPullParser.START_TAG || event == XmlPullParser.END_TAG
                        ? tag(parser) : "";
                if (event == XmlPullParser.START_TAG && "si".equals(name)) {
                    inStringItem = true;
                    phoneticDepth = 0;
                    current = new StringBuilder();
                } else if (event == XmlPullParser.START_TAG && inStringItem && "rPh".equals(name)) {
                    phoneticDepth++;
                } else if (event == XmlPullParser.END_TAG && inStringItem && "rPh".equals(name)) {
                    if (phoneticDepth > 0) phoneticDepth--;
                } else if (event == XmlPullParser.START_TAG && inStringItem && "t".equals(name)) {
                    String text = parser.nextText();
                    if (phoneticDepth == 0) current.append(text);
                } else if (event == XmlPullParser.END_TAG && "si".equals(name)) {
                    values.add(current == null ? "" : unescapeExcel(current.toString()));
                    current = null;
                    inStringItem = false;
                }
                event = parser.next();
            }
        } catch (Exception e) {
            throw new IOException("无法解析 sharedStrings.xml", e);
        }
        return values;
    }

    // ------------------------------------------------------------------
    // Worksheet cells -> fixed FLES columns A-H
    // ------------------------------------------------------------------

    private static List<FlesDatabase.Sentence> parseWorksheet(byte[] xml,
                                                               List<String> sharedStrings) throws IOException {
        List<FlesDatabase.Sentence> result = new ArrayList<FlesDatabase.Sentence>();
        Set<String> seen = new HashSet<String>();
        XmlPullParser parser = newParser(xml);

        int rowNumber = 0;
        Map<Integer, String> cells = null;
        int currentColumn = -1;
        int lastColumn = -1;          // 单元格缺少 r 属性时，按顺序推算列号
        String currentType = "";
        StringBuilder inlineText = null;
        int phoneticDepth = 0;
        String valueText = null;
        boolean hasValue = false;
        int dataRows = 0;

        try {
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                String name = event == XmlPullParser.START_TAG || event == XmlPullParser.END_TAG
                        ? tag(parser) : "";
                if (event == XmlPullParser.START_TAG && "row".equals(name)) {
                    String rowAttr = parser.getAttributeValue(null, "r");
                    rowNumber = parseInt(rowAttr, rowNumber + 1);
                    cells = new HashMap<Integer, String>();
                    lastColumn = -1;
                } else if (event == XmlPullParser.START_TAG && "c".equals(name) && cells != null) {
                    currentColumn = columnIndex(parser.getAttributeValue(null, "r"));
                    if (currentColumn < 0) currentColumn = lastColumn + 1;
                    lastColumn = currentColumn;
                    currentType = parser.getAttributeValue(null, "t");
                    if (currentType == null) currentType = "";
                    inlineText = new StringBuilder();
                    phoneticDepth = 0;
                    valueText = null;
                    hasValue = false;
                } else if (event == XmlPullParser.START_TAG && currentColumn >= 0 && "rPh".equals(name)) {
                    phoneticDepth++;
                } else if (event == XmlPullParser.END_TAG && currentColumn >= 0 && "rPh".equals(name)) {
                    if (phoneticDepth > 0) phoneticDepth--;
                } else if (event == XmlPullParser.START_TAG && currentColumn >= 0 && "v".equals(name)) {
                    valueText = parser.nextText();
                    hasValue = true;
                } else if (event == XmlPullParser.START_TAG && currentColumn >= 0 &&
                        "inlineStr".equals(currentType) && "t".equals(name)) {
                    String text = parser.nextText();
                    if (phoneticDepth == 0) inlineText.append(text);
                } else if (event == XmlPullParser.END_TAG && "c".equals(name) && currentColumn >= 0 && cells != null) {
                    String raw = hasValue ? valueText : inlineText.toString();
                    String value = decodeCellValue(raw, currentType, sharedStrings);
                    cells.put(currentColumn, value);
                    currentColumn = -1;
                    currentType = "";
                    inlineText = null;
                    valueText = null;
                    hasValue = false;
                } else if (event == XmlPullParser.END_TAG && "row".equals(name) && cells != null) {
                    // Row 1 is the column-heading row. The data begins at row 2.
                    if (rowNumber > 1) {
                        dataRows++;
                        if (dataRows > MAX_DATA_ROWS) {
                            throw new IOException("Excel 数据超过 100000 行，已停止导入");
                        }
                        FlesDatabase.Sentence sentence = makeSentence(cells);
                        if (sentence != null) {
                            String key = FlesDatabase.normalizeSentenceKey(sentence.targetSentence);
                            if (key.length() > 0 && seen.add(key)) {
                                sentence.sentenceKey = key;
                                sentence.targetSentence = key;
                                result.add(sentence);
                            }
                        }
                    }
                    cells = null;
                }
                event = parser.next();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("无法解析 Excel 第一个工作表", e);
        }
        return result;
    }

    private static FlesDatabase.Sentence makeSentence(Map<Integer, String> cells) {
        // A serial, B section, C foreign sentence, D native meaning,
        // E coverage, F memory hint, G reading, H optional audio filename.
        String foreign = cell(cells, 2).trim();
        if (foreign.length() == 0) return null;

        FlesDatabase.Sentence sentence = new FlesDatabase.Sentence();
        sentence.serialNo = parseInt(cell(cells, 0), 0);
        sentence.section = cell(cells, 1).trim();
        sentence.targetSentence = foreign;
        sentence.nativeMeaning = cell(cells, 3).trim();
        sentence.coverage = cell(cells, 4).trim();
        sentence.memoryHint = cell(cells, 5).trim();
        sentence.pronunciationGuide = cell(cells, 6).trim();
        sentence.pronunciationAudioFile = cell(cells, 7).trim(); // H may be missing.
        sentence.isActive = true;
        return sentence;
    }

    private static String cell(Map<Integer, String> cells, int column) {
        String value = cells.get(column);
        return value == null ? "" : value;
    }

    private static String decodeCellValue(String raw, String type, List<String> sharedStrings) throws IOException {
        if (raw == null) return "";
        if ("e".equals(type)) return "";          // #REF!、#N/A 等错误值不当作正文
        if ("s".equals(type)) {
            if (raw.trim().length() == 0) return "";   // 有 t="s" 但没有索引的空单元格
            int index = parseInt(raw, -1);
            if (index < 0 || index >= sharedStrings.size()) {
                throw new IOException("Excel 的共享字符串索引无效：" + raw);
            }
            return sharedStrings.get(index);
        }
        // inlineStr is collected from <is><t>...</t></is>.
        // 'str' formula result and ordinary numeric/text cells use <v>.
        return unescapeExcel(raw);
    }

    private static int columnIndex(String cellReference) {
        if (cellReference == null || cellReference.length() == 0) return -1;
        int value = 0;
        int letters = 0;
        for (int i = 0; i < cellReference.length(); i++) {
            char c = cellReference.charAt(i);
            if (c >= 'a' && c <= 'z') c = (char) (c - 'a' + 'A');
            if (c < 'A' || c > 'Z') break;
            value = value * 26 + (c - 'A' + 1);
            letters++;
        }
        return letters == 0 ? -1 : value - 1;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            String trimmed = value.trim();
            if (trimmed.length() == 0) return fallback;
            return (int) Double.parseDouble(trimmed);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static XmlPullParser newParser(byte[] xml) throws IOException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            parser.setInput(new ByteArrayInputStream(xml), "UTF-8");
            return parser;
        } catch (Exception e) {
            throw new IOException("无法初始化 XML 解析器", e);
        }
    }
}
