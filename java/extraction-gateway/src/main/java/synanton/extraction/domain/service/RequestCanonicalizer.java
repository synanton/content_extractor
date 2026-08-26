package synanton.extraction.domain.service;

import synanton.extraction.domain.model.ExtractionItemCommand;
import synanton.extraction.domain.model.SubmitExtractionCommand;
import synanton.extraction.spi.model.ExtractionOptions;
import synanton.extraction.spi.model.ObjectRef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.stream.Collectors;

/**
 * Computes a stable hash over immutable submit semantics for idempotency.
 */
public class RequestCanonicalizer {

    public String canonicalize(SubmitExtractionCommand command) {
        StringBuilder builder = new StringBuilder();
        builder.append(command.tenantId()).append('|');
        builder.append(command.priority()).append('|');
        builder.append(formatInstant(command.expiresAt())).append('|');
        builder.append(command.items().stream()
                .map(this::canonicalizeItem)
                .collect(Collectors.joining(";")));
        return sha256Hex(builder.toString());
    }

    private String canonicalizeItem(ExtractionItemCommand item) {
        ObjectRef source = item.source();
        ExtractionOptions options = item.options() != null ? item.options() : ExtractionOptions.defaults();
        return item.contentRefId()
                + '|' + item.mediaType()
                + '|' + source.bucket()
                + '|' + source.key()
                + '|' + nvl(source.version())
                + '|' + source.sha256()
                + '|' + source.sizeBytes()
                + '|' + formatOptions(options);
    }

    private static String formatOptions(ExtractionOptions options) {
        return String.join(",",
                formatOption("ocr", options.ocr()),
                formatOption("transcription", options.transcription()),
                formatOption("layout", options.layout()),
                formatOption("tables", options.tables()),
                formatOption("embeddedImages", options.embeddedImages()),
                formatOption("sceneAnalysis", options.sceneAnalysis()),
                "language=" + nvl(options.language()));
    }

    private static String formatOption(String name, Boolean value) {
        return name + '=' + (value == null ? "unset" : value);
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? "unset" : instant.toString();
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
