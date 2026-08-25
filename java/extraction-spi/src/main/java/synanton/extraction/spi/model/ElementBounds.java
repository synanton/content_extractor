package synanton.extraction.spi.model;

/**
 * Location on a page in PDF user-space points, origin bottom-left.
 * {@code page=0} when the format has no pages.
 *
 * @param page the 1-based page number; {@code 0} when the format has no page concept
 * @param x0   the left edge x-coordinate in PDF user-space points
 * @param y0   the bottom edge y-coordinate in PDF user-space points
 * @param x1   the right edge x-coordinate in PDF user-space points
 * @param y1   the top edge y-coordinate in PDF user-space points
 */
public record ElementBounds(int page, double x0, double y0, double x1, double y1) {

    /**
     * Returns an {@link ElementBounds} instance representing an absent or unknown location.
     * All coordinate fields are {@code 0.0} and {@code page} is {@code 0}.
     *
     * @return a zero-valued bounds instance
     */
    public static ElementBounds absent() {
        return new ElementBounds(0, 0.0, 0.0, 0.0, 0.0);
    }
}
