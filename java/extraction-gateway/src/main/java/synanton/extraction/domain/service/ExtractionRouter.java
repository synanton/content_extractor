package synanton.extraction.domain.service;

import synanton.extraction.spi.port.ModalityAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Selects a {@link ModalityAdapter} by media type. Prefers a real adapter over a capability-declining stub.
 */
public class ExtractionRouter {

    private final List<ModalityAdapter> adapters;

    public ExtractionRouter(List<ModalityAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    public Optional<ModalityAdapter> route(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return Optional.empty();
        }
        List<ModalityAdapter> matches = new ArrayList<>();
        for (ModalityAdapter adapter : adapters) {
            if (adapter.supports(mediaType)) {
                matches.add(adapter);
            }
        }
        return matches.stream()
                .filter(a -> !isStub(a))
                .findFirst()
                .or(() -> matches.stream().findFirst());
    }

    public List<ModalityAdapter> productiveAdapters() {
        return adapters.stream().filter(a -> !isStub(a)).toList();
    }

    private static boolean isStub(ModalityAdapter adapter) {
        String id = adapter.processorId();
        return id != null && id.toLowerCase(Locale.ROOT).contains("stub");
    }
}
