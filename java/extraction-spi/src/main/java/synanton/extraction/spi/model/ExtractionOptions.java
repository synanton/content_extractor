package synanton.extraction.spi.model;

/**
 * Tri-state extraction options. {@code null} means the plane decides; {@code false} means do NOT
 * apply this feature even when it would normally be used; {@code true} means the feature is
 * explicitly requested.
 *
 * @param ocr              whether to apply optical character recognition
 * @param transcription    whether to apply audio/video transcription
 * @param layout           whether to apply layout analysis
 * @param tables           whether to extract table structures
 * @param embeddedImages   whether to process embedded images
 * @param sceneAnalysis    whether to apply scene analysis
 * @param language         the BCP-47 language hint for the content (null means auto-detect)
 */
public record ExtractionOptions(
        Boolean ocr,
        Boolean transcription,
        Boolean layout,
        Boolean tables,
        Boolean embeddedImages,
        Boolean sceneAnalysis,
        String language) {

    /**
     * Returns an {@link ExtractionOptions} instance with all fields set to {@code null},
     * meaning the extraction plane decides the appropriate behaviour for each feature.
     *
     * @return a fully-unset options instance
     */
    public static ExtractionOptions defaults() {
        return new ExtractionOptions(null, null, null, null, null, null, null);
    }
}
