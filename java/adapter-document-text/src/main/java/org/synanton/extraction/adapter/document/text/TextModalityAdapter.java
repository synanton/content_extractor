package org.synanton.extraction.adapter.document.text;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.synanton.extraction.spi.model.AdapterResult;
import org.synanton.extraction.spi.model.ContentOrigin;
import org.synanton.extraction.spi.model.ElementBounds;
import org.synanton.extraction.spi.model.ElementType;
import org.synanton.extraction.spi.model.ExtractionFailure;
import org.synanton.extraction.spi.model.ExtractionOptions;
import org.synanton.extraction.spi.model.ExtractionRequest;
import org.synanton.extraction.spi.model.FeatureOutcome;
import org.synanton.extraction.spi.model.NormalizedDocument;
import org.synanton.extraction.spi.model.NormalizedElement;
import org.synanton.extraction.spi.model.NormalizedSourceDescriptor;
import org.synanton.extraction.spi.model.SourceSpan;
import org.synanton.extraction.spi.port.ModalityAdapter;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link ModalityAdapter} for plain-text and text-like document formats.
 *
 * <p>Backed by Apache Tika's {@link AutoDetectParser}. Supports:
 * <ul>
 *   <li>{@code text/plain}</li>
 *   <li>{@code text/html}</li>
 *   <li>{@code text/csv}</li>
 *   <li>{@code text/xhtml+xml}</li>
 *   <li>{@code application/xhtml+xml}</li>
 *   <li>{@code application/epub+zip}</li>
 * </ul>
 *
 * <p>Markdown is parsed structurally into headings, paragraphs, lists, tables and fenced code
 * blocks. Other text-like formats are split at blank lines into {@link ElementType#PARAGRAPH}
 * elements. No layout analysis or OCR is performed.
 */
@Slf4j
public class TextModalityAdapter implements ModalityAdapter {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "text/plain",
            "text/markdown",
            "text/x-markdown",
            "text/html",
            "text/csv",
            "text/xhtml+xml",
            "application/xhtml+xml",
            "application/epub+zip"
    );

    @Override
    public boolean supports(String mediaType) {
        return SUPPORTED_TYPES.contains(mediaType);
    }

    @Override
    public String processorId() {
        return "text-adapter-tika";
    }

    @Override
    public AdapterResult extract(ExtractionRequest request, InputStream source) {
        try {
            byte[] bytes = source.readAllBytes();
            Metadata tikaMetadata = new Metadata();
            tikaMetadata.set(Metadata.CONTENT_TYPE, request.mediaType());

            List<NormalizedElement> elements;
            Map<String, String> markdownMetadata = Map.of();
            if (isMarkdown(request.mediaType())) {
                if (request.source() == null) {
                    throw new IllegalArgumentException(
                            "Markdown source evidence requires an immutable source identity");
                }
                MarkdownSource markdown = readMarkdown(bytes);
                elements = buildMarkdownElements(markdown);
                markdownMetadata = extractMarkdownMetadata(markdown.text());
            } else {
                // Use an unlimited buffer (-1) to capture the full document body.
                BodyContentHandler handler = new BodyContentHandler(-1);
                AutoDetectParser parser = new AutoDetectParser();
                parser.parse(new ByteArrayInputStream(bytes), handler, tikaMetadata, new ParseContext());
                elements = buildElements(handler.toString().trim());
            }

            Map<String, String> docMetadata = extractDocMetadata(tikaMetadata);
            docMetadata.putAll(markdownMetadata);

            String flattenedText = elements.stream()
                    .map(NormalizedElement::text)
                    .filter(t -> t != null && !t.isBlank())
                    .reduce("", (a, b) -> a.isBlank() ? b : a + "\n" + b);

            NormalizedDocument document = new NormalizedDocument(
                    request.mediaType(), docMetadata, elements, flattenedText, sourceDescriptor(request));

            Map<String, FeatureOutcome> featureStates = buildFeatureStates(request, elements);
            return AdapterResult.success(document, featureStates);

        } catch (Exception e) {
            log.warn("Text extraction failed for contentRefId={}: {}",
                    request.contentRefId(), e.getMessage());
            return AdapterResult.failed(
                    ExtractionFailure.extractionFailed(e.getMessage()),
                    Map.of("text", FeatureOutcome.FAILED)
            );
        }
    }

    private static boolean isMarkdown(String mediaType) {
        return "text/markdown".equals(mediaType) || "text/x-markdown".equals(mediaType);
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})[ \\t]+(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)([-*+]|\\d+[.)])[ \\t]+(.*)$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```+|~~~+)\\s*([^`]*)$");
    private static final Pattern METADATA_LINE = Pattern.compile("^\\*\\*([^*]{1,64}):\\*\\*[ \\t]*(.*)$");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    private static final Pattern TABLE_SEPARATOR_CELL = Pattern.compile("^:?-{3,}:?$");

    /**
     * Parses Markdown into structural elements without reconstructing structure from Tika text.
     * HTML comments and YAML front matter are treated as metadata, not structural content.
     */
    private List<NormalizedElement> buildMarkdownElements(MarkdownSource markdown) {
        List<MarkdownLine> lines = markdown.lines();
        if (lines.isEmpty()) {
            return List.of();
        }
        List<NormalizedElement> elements = new ArrayList<>();
        int nextId = 1;
        int i = 0;
        boolean metadataPreamble = true;

        // Skip YAML front matter at the start of the document.
        if (lines.getFirst().text().strip().equals("---")) {
            for (i = 1; i < lines.size(); i++) {
                if (lines.get(i).text().strip().equals("---")) { i++; break; }
            }
        }

        while (i < lines.size()) {
            MarkdownLine line = lines.get(i);
            String raw = line.text();
            String stripped = raw.strip();

            if (stripped.isEmpty()) { i++; continue; }
            if (metadataPreamble && METADATA_LINE.matcher(raw).matches()) {
                i++;
                continue;
            }
            if (stripped.startsWith("<!--")) {
                while (i < lines.size() && !lines.get(i).text().contains("-->")) i++;
                i++;
                continue;
            }

            Matcher fence = FENCE.matcher(raw);
            if (fence.matches()) {
                String fenceMarker = fence.group(1);
                String language = fence.group(2).strip();
                int startByte = line.startByte();
                int endByte = line.contentEndByte();
                StringBuilder code = new StringBuilder();
                boolean firstCodeLine = true;
                i++;
                while (i < lines.size() && !isClosingFence(lines.get(i).text(), fenceMarker)) {
                    if (!firstCodeLine) code.append('\n');
                    code.append(lines.get(i).text());
                    firstCodeLine = false;
                    endByte = lines.get(i).contentEndByte();
                    i++;
                }
                if (i < lines.size()) {
                    endByte = lines.get(i).contentEndByte();
                    i++;
                }
                String id = "e" + nextId++;
                elements.add(new NormalizedElement(
                        id, ElementType.CODE_BLOCK, ElementBounds.absent(),
                        code.toString(), ContentOrigin.EMBEDDED_TEXT, 0, List.of(),
                        language.isEmpty() ? Map.of() : Map.of("language", language), null,
                        List.of(sourceSpan(id, startByte, endByte))));
                continue;
            }

            Matcher heading = HEADING.matcher(raw);
            if (heading.matches()) {
                int level = heading.group(1).length();
                if (level >= 2) metadataPreamble = false;
                String id = "e" + nextId++;
                elements.add(new NormalizedElement(
                        id, ElementType.HEADING, ElementBounds.absent(),
                        heading.group(2).strip(), ContentOrigin.EMBEDDED_TEXT,
                        level, List.of(), Map.of(), null,
                        List.of(sourceSpan(id, line.startByte(), line.contentEndByte()))));
                i++;
                continue;
            }

            if (isTableStart(lines, i)) {
                i = parseTable(lines, i, elements, nextId);
                nextId = elements.stream().mapToInt(e -> idNumber(e.id())).max().orElse(0) + 1;
                continue;
            }

            if (LIST_ITEM.matcher(raw).matches()) {
                i = parseList(lines, i, elements, nextId);
                nextId = elements.stream().mapToInt(e -> idNumber(e.id())).max().orElse(0) + 1;
                continue;
            }

            StringBuilder paragraph = new StringBuilder();
            int startByte = line.startByte();
            int endByte = line.contentEndByte();
            while (i < lines.size()) {
                MarkdownLine current = lines.get(i);
                String currentStripped = current.text().strip();
                if (currentStripped.isEmpty() || currentStripped.startsWith("<!--")
                        || HEADING.matcher(current.text()).matches()
                        || LIST_ITEM.matcher(current.text()).matches()
                        || FENCE.matcher(current.text()).matches()) {
                    break;
                }
                if (!paragraph.isEmpty()) paragraph.append('\n');
                paragraph.append(currentStripped);
                endByte = current.contentEndByte();
                i++;
            }
            if (!paragraph.isEmpty()) {
                String id = "e" + nextId++;
                elements.add(new NormalizedElement(
                        id, ElementType.PARAGRAPH, ElementBounds.absent(),
                        paragraph.toString(), ContentOrigin.EMBEDDED_TEXT, 0,
                        List.of(), Map.of(), null,
                        List.of(sourceSpan(id, startByte, endByte))));
            }
        }
        return elements;
    }

    private static boolean isTableStart(List<MarkdownLine> lines, int index) {
        if (index + 1 >= lines.size()) {
            return false;
        }
        if (!TABLE_ROW.matcher(lines.get(index).text()).matches()
                || !TABLE_ROW.matcher(lines.get(index + 1).text()).matches()) {
            return false;
        }
        String separator = lines.get(index + 1).text().strip();
        String[] cells = separator.substring(1, separator.length() - 1).split("\\|", -1);
        if (cells.length == 0) {
            return false;
        }
        for (String cell : cells) {
            if (!TABLE_SEPARATOR_CELL.matcher(cell.strip()).matches()) {
                return false;
            }
        }
        return true;
    }

    private int parseTable(List<MarkdownLine> lines, int start,
                           List<NormalizedElement> elements, int firstId) {
        MarkdownLine header = lines.get(start);
        int tableStart = header.startByte();
        int tableEnd = lines.get(start + 1).contentEndByte();
        int i = start + 1;
        // Separator row is structural syntax, not a data row.
        i++;
        List<TableRow> rows = new ArrayList<>();
        rows.add(tableRow(header, true));
        while (i < lines.size() && TABLE_ROW.matcher(lines.get(i).text()).matches()) {
            rows.add(tableRow(lines.get(i), false));
            tableEnd = lines.get(i).contentEndByte();
            i++;
        }

        int nextId = firstId + 1;
        List<String> rowIds = new ArrayList<>();
        List<NormalizedElement> rowElements = new ArrayList<>();
        StringBuilder tableText = new StringBuilder();
        for (TableRow row : rows) {
            String rowId = "e" + nextId++;
            List<String> cellIds = new ArrayList<>();
            List<NormalizedElement> cellElements = new ArrayList<>();
            for (TableCell cell : row.cells()) {
                String cellId = "e" + nextId++;
                cellIds.add(cellId);
                cellElements.add(new NormalizedElement(
                        cellId, ElementType.TABLE_CELL, ElementBounds.absent(), cell.text(),
                        ContentOrigin.EMBEDDED_TEXT, 0, List.of(),
                        cell.header() ? Map.of("header", "true") : Map.of(), null,
                        cell.sourceSpan() == null ? List.of()
                                : List.of(cell.sourceSpan())));
            }
            String rowText = row.cells().stream().map(TableCell::text).reduce((a, b) -> a + " | " + b).orElse("");
            if (!tableText.isEmpty()) tableText.append('\n');
            tableText.append(rowText);
            rowIds.add(rowId);
            rowElements.add(new NormalizedElement(
                    rowId, ElementType.TABLE_ROW, ElementBounds.absent(), rowText,
                    ContentOrigin.EMBEDDED_TEXT, 0, cellIds,
                    row.header() ? Map.of("header", "true") : Map.of(), null,
                    List.of(sourceSpanForRange(rowId, row.line(), row.line().text().length()))));
            rowElements.addAll(cellElements);
        }
        String tableId = "e" + firstId;
        elements.add(new NormalizedElement(
                tableId, ElementType.TABLE, ElementBounds.absent(), tableText.toString(),
                ContentOrigin.EMBEDDED_TEXT, 0, rowIds, Map.of(), null,
                List.of(sourceSpan(tableId, tableStart, tableEnd))));
        elements.addAll(rowElements);
        return i;
    }

    private static TableRow tableRow(MarkdownLine line, boolean header) {
        String raw = line.text();
        int firstPipe = raw.indexOf('|');
        int lastPipe = raw.lastIndexOf('|');
        List<TableCell> cells = new ArrayList<>();
        if (firstPipe >= 0 && lastPipe > firstPipe) {
            int cellStart = firstPipe + 1;
            boolean inCodeSpan = false;
            int codeDelimiterLength = 0;
            int cursor = firstPipe + 1;
            while (cursor < lastPipe) {
                char ch = raw.charAt(cursor);
                if (ch == '`') {
                    int run = 0;
                    while (cursor + run < lastPipe && raw.charAt(cursor + run) == '`') {
                        run++;
                    }
                    if (!inCodeSpan) {
                        inCodeSpan = true;
                        codeDelimiterLength = run;
                    } else if (run == codeDelimiterLength) {
                        inCodeSpan = false;
                        codeDelimiterLength = 0;
                    }
                    cursor += run;
                    continue;
                }
                if (ch == '\\' && cursor + 1 < lastPipe) {
                    cursor += 2;
                    continue;
                }
                if (ch == '|' && !inCodeSpan) {
                    addTableCell(cells, line, header, cellStart, cursor);
                    cellStart = cursor + 1;
                }
                cursor++;
            }
            addTableCell(cells, line, header, cellStart, lastPipe);
        }
        return new TableRow(line, header, cells);
    }

    private static void addTableCell(List<TableCell> cells, MarkdownLine line,
                                     boolean header, int rawStart, int rawEnd) {
        int startChar = rawStart;
        int endChar = rawEnd;
        while (startChar < endChar && Character.isWhitespace(line.text().charAt(startChar))) startChar++;
        while (endChar > startChar && Character.isWhitespace(line.text().charAt(endChar - 1))) endChar--;
        String text = line.text().substring(startChar, endChar);
        SourceSpan span = null;
        if (endChar > startChar) {
            span = sourceSpanForRange("cell-" + line.startByte() + "-" + startChar,
                    line, startChar, endChar);
        }
        cells.add(new TableCell(text, header, span));
    }

    private static SourceSpan sourceSpanForRange(String id, MarkdownLine line, int charEnd) {
        return sourceSpanForRange(id, line, 0, charEnd);
    }

    private static SourceSpan sourceSpanForRange(String id, MarkdownLine line, int charStart, int charEnd) {
        int start = line.startByte() + line.text().substring(0, charStart).getBytes(StandardCharsets.UTF_8).length;
        int end = line.startByte() + line.text().substring(0, charEnd).getBytes(StandardCharsets.UTF_8).length;
        return new SourceSpan(id, start, end);
    }

    private record TableRow(MarkdownLine line, boolean header, List<TableCell> cells) { }

    private record TableCell(String text, boolean header, SourceSpan sourceSpan) { }

    private int parseList(List<MarkdownLine> lines, int start, List<NormalizedElement> elements, int firstId) {
        List<NormalizedElement> items = new ArrayList<>();
        List<NormalizedElement> parents = new ArrayList<>();
        int id = firstId + 1;
        boolean ordered = false;
        int i = start;
        int listStartByte = lines.get(start).startByte();
        int listEndByte = lines.get(start).contentEndByte();
        while (i < lines.size()) {
            MarkdownLine line = lines.get(i);
            Matcher m = LIST_ITEM.matcher(line.text());
            if (!m.matches()) break;
            int level = (m.group(1).length() / 2) + 1;
            if (items.isEmpty()) ordered = Character.isDigit(m.group(2).charAt(0));
            String itemText = m.group(3).strip();
            List<String> children = new ArrayList<>();
            while (parents.size() >= level) parents.remove(parents.size() - 1);
            if (!parents.isEmpty()) {
                NormalizedElement parent = parents.get(parents.size() - 1);
                children = new ArrayList<>(parent.childIds());
                children.add("e" + id);
                NormalizedElement updated = new NormalizedElement(parent.id(), parent.type(), parent.bounds(),
                        parent.text(), parent.contentOrigin(), parent.level(), children,
                        parent.attributes(), parent.alternateRepresentation(), parent.sourceSpans());
                items.set(items.indexOf(parent), updated);
                parents.set(parents.size() - 1, updated);
            }
            String itemId = "e" + id++;
            NormalizedElement item = new NormalizedElement(
                    itemId, ElementType.LIST_ITEM, ElementBounds.absent(), itemText,
                    ContentOrigin.EMBEDDED_TEXT, level, List.of(), Map.of(), null,
                    List.of(sourceSpan(itemId, line.startByte(), line.contentEndByte())));
            items.add(item);
            parents.add(item);
            listEndByte = line.contentEndByte();
            i++;
        }
        String listId = "e" + firstId;
        List<String> topLevel = items.stream().filter(item -> item.level() == 1)
                .map(NormalizedElement::id).toList();
        elements.add(new NormalizedElement(listId, ElementType.LIST, ElementBounds.absent(), "",
                ContentOrigin.EMBEDDED_TEXT, 1, topLevel,
                ordered ? Map.of("ordered", "true") : Map.of("ordered", "false"), null,
                List.of(sourceSpan(listId, listStartByte, listEndByte))));
        elements.addAll(items);
        return i;
    }

    private static boolean isClosingFence(String line, String openingFence) {
        int leadingSpaces = 0;
        while (leadingSpaces < line.length() && line.charAt(leadingSpaces) == ' ') {
            leadingSpaces++;
        }
        if (leadingSpaces > 3) {
            return false;
        }
        if (leadingSpaces < line.length() && line.charAt(leadingSpaces) == '\t') {
            return false;
        }
        String candidate = line.substring(leadingSpaces);
        if (candidate.isEmpty() || candidate.charAt(0) != openingFence.charAt(0)) {
            return false;
        }
        int run = 0;
        while (run < candidate.length() && candidate.charAt(run) == openingFence.charAt(0)) {
            run++;
        }
        if (run < openingFence.length()) {
            return false;
        }
        for (int i = run; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    private static SourceSpan sourceSpan(String elementId, int startByte, int endByte) {
        return new SourceSpan("span-" + elementId, startByte, endByte);
    }

    private static NormalizedSourceDescriptor sourceDescriptor(ExtractionRequest request) {
        if (request.source() == null) {
            return null;
        }
        String format = isMarkdown(request.mediaType()) ? "markdown" : request.mediaType();
        return new NormalizedSourceDescriptor(
                request.mediaType(), format, request.source().sha256(),
                request.source().version(), request.source().key());
    }

    private record MarkdownLine(String text, int startByte, int contentEndByte) { }

    private record MarkdownSource(String text, List<MarkdownLine> lines) { }

    private static MarkdownSource readMarkdown(byte[] bytes) throws CharacterCodingException {
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        List<MarkdownLine> lines = new ArrayList<>();
        int lineStart = 0;
        int i = 0;
        while (i < bytes.length) {
            byte b = bytes[i];
            if (b == '\n' || b == '\r') {
                int contentEnd = i;
                int terminatorEnd = i + 1;
                if (b == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') {
                    terminatorEnd = i + 2;
                    i++;
                }
                String lineText = new String(bytes, lineStart, contentEnd - lineStart, StandardCharsets.UTF_8);
                if (lineStart == 0 && lineText.startsWith("\uFEFF")) {
                    lineText = lineText.substring(1);
                }
                lines.add(new MarkdownLine(lineText, lineStart, contentEnd));
                lineStart = terminatorEnd;
            }
            i++;
        }
        if (lineStart < bytes.length) {
            String lineText = new String(bytes, lineStart, bytes.length - lineStart, StandardCharsets.UTF_8);
            if (lineStart == 0 && lineText.startsWith("\uFEFF")) {
                lineText = lineText.substring(1);
            }
            lines.add(new MarkdownLine(lineText, lineStart, bytes.length));
        }
        return new MarkdownSource(text, lines);
    }

    private static int idNumber(String id) {
        try {
            return Integer.parseInt(id.substring(1));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private Map<String, String> extractMarkdownMetadata(String markdown) {
        Map<String, String> metadata = new LinkedHashMap<>();
        String text = markdown.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        for (String line : text.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.startsWith("## ")) break;
            Matcher m = METADATA_LINE.matcher(line);
            if (m.matches()) {
                metadata.put(m.group(1).strip(), m.group(2).strip());
            }
        }
        return metadata;
    }

    /**
     * Splits the Tika-extracted text at blank lines and wraps each paragraph
     * in a {@link NormalizedElement}.
     */
    private List<NormalizedElement> buildElements(String text) {
        if (text.isBlank()) {
            return List.of();
        }
        String[] paragraphs = text.split("\\n\\s*\\n+");
        List<NormalizedElement> elements = new ArrayList<>();
        for (int i = 0; i < paragraphs.length; i++) {
            String para = paragraphs[i].strip();
            if (!para.isBlank()) {
                elements.add(new NormalizedElement(
                        "e" + (i + 1),
                        ElementType.PARAGRAPH,
                        ElementBounds.absent(),
                        para,
                        ContentOrigin.EMBEDDED_TEXT,
                        0,
                        List.of(),
                        Map.of(),
                        null
                ));
            }
        }
        return elements;
    }

    /**
     * Copies all non-blank Tika metadata entries into a {@link LinkedHashMap} in insertion order.
     */
    private Map<String, String> extractDocMetadata(Metadata tikaMetadata) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : tikaMetadata.names()) {
            String value = tikaMetadata.get(name);
            if (value != null && !value.isBlank()) {
                result.put(name, value);
            }
        }
        return result;
    }

    /**
     * Derives the {@link FeatureOutcome} map from the request options and the media type.
     */
    private Map<String, FeatureOutcome> buildFeatureStates(ExtractionRequest request,
                                                           List<NormalizedElement> elements) {
        Map<String, FeatureOutcome> states = new LinkedHashMap<>();
        states.put("text", FeatureOutcome.APPLIED);

        ExtractionOptions options = request.options() != null
                ? request.options()
                : ExtractionOptions.defaults();

        boolean isStructured = "text/html".equals(request.mediaType())
                || "application/epub+zip".equals(request.mediaType())
                || "application/xhtml+xml".equals(request.mediaType())
                || "text/xhtml+xml".equals(request.mediaType());
        states.put("layout", isStructured ? FeatureOutcome.APPLIED : FeatureOutcome.NOT_APPLICABLE);

        // OCR is not applicable to digital-text formats.
        states.put("ocr", FeatureOutcome.NOT_APPLICABLE);

        // Markdown pipe tables are parsed structurally; other text formats are not.
        boolean hasTable = elements.stream().anyMatch(el -> el.type() == ElementType.TABLE);
        states.put("tables", Boolean.TRUE.equals(options.tables())
                ? (isMarkdown(request.mediaType())
                    ? (hasTable ? FeatureOutcome.APPLIED : FeatureOutcome.NOT_APPLICABLE)
                    : FeatureOutcome.UNSUPPORTED)
                : FeatureOutcome.NOT_REQUESTED);

        // Transcription is not applicable to document formats.
        states.put("transcription", Boolean.TRUE.equals(options.transcription())
                ? FeatureOutcome.NOT_APPLICABLE
                : FeatureOutcome.NOT_REQUESTED);

        // Embedded-image extraction is not implemented in this adapter.
        states.put("embeddedImages", Boolean.TRUE.equals(options.embeddedImages())
                ? FeatureOutcome.UNSUPPORTED
                : FeatureOutcome.NOT_REQUESTED);

        return states;
    }
}
