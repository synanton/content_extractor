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
import org.synanton.extraction.spi.port.ModalityAdapter;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
 * <p>Markdown is parsed structurally into headings, paragraphs, lists and fenced code blocks.
 * Other text-like formats are split at blank lines into {@link ElementType#PARAGRAPH} elements.
 * No layout analysis, OCR, or table extraction is performed.
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
                String markdown = new String(bytes, StandardCharsets.UTF_8);
                elements = buildMarkdownElements(markdown);
                markdownMetadata = extractMarkdownMetadata(markdown);
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
                    request.mediaType(), docMetadata, elements, flattenedText);

            Map<String, FeatureOutcome> featureStates = buildFeatureStates(request);
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

    /**
     * Parses Markdown into structural elements without reconstructing structure from Tika text.
     * HTML comments and YAML front matter are treated as metadata, not structural content.
     */
    private List<NormalizedElement> buildMarkdownElements(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        String text = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = text.split("\n", -1);
        List<NormalizedElement> elements = new ArrayList<>();
        int nextId = 1;
        int i = 0;
        boolean metadataPreamble = true;

        // Skip YAML front matter at the start of the document.
        if (lines.length > 0 && lines[0].strip().equals("---")) {
            for (i = 1; i < lines.length; i++) {
                if (lines[i].strip().equals("---")) { i++; break; }
            }
        }

        while (i < lines.length) {
            String line = lines[i];
            String stripped = line.strip();

            if (stripped.isEmpty()) { i++; continue; }
            if (metadataPreamble && METADATA_LINE.matcher(line).matches()) {
                i++;
                continue;
            }
            if (stripped.startsWith("<!--")) {
                while (i < lines.length && !lines[i].contains("-->")) i++;
                i++;
                continue;
            }

            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                String fenceMarker = fence.group(1);
                String language = fence.group(2).strip();
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].strip().startsWith(fenceMarker)) {
                    if (!code.isEmpty()) code.append('\n');
                    code.append(lines[i]);
                    i++;
                }
                if (i < lines.length) i++;
                elements.add(new NormalizedElement(
                        "e" + nextId++, ElementType.CODE_BLOCK, ElementBounds.absent(),
                        code.toString(), ContentOrigin.EMBEDDED_TEXT, 0, List.of(),
                        language.isEmpty() ? Map.of() : Map.of("language", language), null));
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                int level = heading.group(1).length();
                if (level >= 2) metadataPreamble = false;
                elements.add(new NormalizedElement(
                        "e" + nextId++, ElementType.HEADING, ElementBounds.absent(),
                        heading.group(2).strip(), ContentOrigin.EMBEDDED_TEXT,
                        level, List.of(), Map.of(), null));
                i++;
                continue;
            }

            if (LIST_ITEM.matcher(line).matches()) {
                i = parseList(lines, i, elements, nextId);
                nextId = elements.stream().mapToInt(e -> idNumber(e.id())).max().orElse(0) + 1;
                continue;
            }

            StringBuilder paragraph = new StringBuilder();
            while (i < lines.length) {
                String current = lines[i];
                String currentStripped = current.strip();
                if (currentStripped.isEmpty() || currentStripped.startsWith("<!--")
                        || HEADING.matcher(current).matches() || LIST_ITEM.matcher(current).matches()
                        || FENCE.matcher(current).matches()) {
                    break;
                }
                if (!paragraph.isEmpty()) paragraph.append('\n');
                paragraph.append(currentStripped);
                i++;
            }
            if (!paragraph.isEmpty()) {
                elements.add(new NormalizedElement(
                        "e" + nextId++, ElementType.PARAGRAPH, ElementBounds.absent(),
                        paragraph.toString(), ContentOrigin.EMBEDDED_TEXT, 0,
                        List.of(), Map.of(), null));
            }
        }
        return elements;
    }

    private int parseList(String[] lines, int start, List<NormalizedElement> elements, int firstId) {
        List<NormalizedElement> items = new ArrayList<>();
        List<NormalizedElement> parents = new ArrayList<>();
        int id = firstId + 1;
        boolean ordered = false;
        int i = start;
        while (i < lines.length) {
            Matcher m = LIST_ITEM.matcher(lines[i]);
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
                        parent.attributes(), parent.alternateRepresentation());
                items.set(items.indexOf(parent), updated);
                parents.set(parents.size() - 1, updated);
            }
            NormalizedElement item = new NormalizedElement(
                    "e" + id++, ElementType.LIST_ITEM, ElementBounds.absent(), itemText,
                    ContentOrigin.EMBEDDED_TEXT, level, List.of(), Map.of(), null);
            items.add(item);
            parents.add(item);
            i++;
        }
        String listId = "e" + firstId;
        List<String> topLevel = items.stream().filter(item -> item.level() == 1)
                .map(NormalizedElement::id).toList();
        elements.add(new NormalizedElement(listId, ElementType.LIST, ElementBounds.absent(), "",
                ContentOrigin.EMBEDDED_TEXT, 1, topLevel,
                ordered ? Map.of("ordered", "true") : Map.of("ordered", "false"), null));
        elements.addAll(items);
        return i;
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
    private Map<String, FeatureOutcome> buildFeatureStates(ExtractionRequest request) {
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

        // Table extraction is not implemented in this adapter.
        states.put("tables", Boolean.TRUE.equals(options.tables())
                ? FeatureOutcome.UNSUPPORTED
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
