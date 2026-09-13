package org.synanton.extraction.adapter.document.pdf.pdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * In-process client for {@code org.opendataloader:opendataloader-pdf-core}.
 *
 * <p>The library is file-in/file-out: it reads a PDF from a path and writes its JSON
 * (and, if configured, PDF/Markdown/HTML) output into a configured output folder — there
 * is no in-memory return value and no HTTP service involved. This client writes the
 * incoming bytes to a temp file, invokes {@link OpenDataLoaderPDF#processFile}, reads back
 * the generated {@code <basename>.json} file, and cleans up both temp locations regardless
 * of outcome.
 */
public class OpenDataLoaderClient {

    private static final Logger log = LoggerFactory.getLogger(OpenDataLoaderClient.class);

    private final ObjectMapper objectMapper;

    public OpenDataLoaderClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Runs OpenDataLoader against the given PDF bytes and returns the parsed JSON response.
     *
     * @param pdfBytes the raw PDF bytes to process
     * @return the parsed response
     * @throws OpenDataLoaderException if the library fails or the output cannot be read/parsed
     */
    public OdlResponse extract(byte[] pdfBytes) {
        Path inputFile = null;
        Path outputDir = null;
        try {
            inputFile = Files.createTempFile("odl-input-", ".pdf");
            Files.write(inputFile, pdfBytes);
            outputDir = Files.createTempDirectory("odl-output-");

            Config config = new Config();
            config.setOutputFolder(outputDir.toString());
            config.setGenerateJSON(true);

            OpenDataLoaderPDF.processFile(inputFile.toString(), config);

            Path jsonFile = findGeneratedJson(outputDir, inputFile);
            return objectMapper.readValue(jsonFile.toFile(), OdlResponse.class);
        } catch (IOException e) {
            throw new OpenDataLoaderException("Failed to run OpenDataLoader: " + e.getMessage(), e);
        } finally {
            deleteQuietly(inputFile);
            deleteRecursivelyQuietly(outputDir);
        }
    }

    private Path findGeneratedJson(Path outputDir, Path inputFile) throws IOException {
        String expectedName = stripExtension(inputFile.getFileName().toString()) + ".json";
        Path expected = outputDir.resolve(expectedName);
        if (Files.exists(expected)) {
            return expected;
        }
        // Fall back to "any .json OpenDataLoader wrote" in case its naming convention
        // differs from a straight <basename>.json (defensive; the expected path above is
        // what was observed empirically against the real library).
        try (Stream<Path> files = Files.list(outputDir)) {
            return files.filter(p -> p.toString().endsWith(".json"))
                    .findFirst()
                    .orElseThrow(() -> new OpenDataLoaderException(
                            "OpenDataLoader did not produce a JSON output file in " + outputDir));
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temp file {}: {}", path, e.getMessage());
        }
    }

    private static void deleteRecursivelyQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(OpenDataLoaderClient::deleteQuietly);
        } catch (IOException e) {
            log.warn("Failed to delete temp output dir {}: {}", dir, e.getMessage());
        }
    }
}
