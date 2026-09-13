package org.synanton.extraction.extraction.domain.service;

import org.junit.jupiter.api.Test;
import org.synanton.extraction.extraction.domain.service.ExtractionRouter;
import org.synanton.extraction.spi.spi.model.AdapterResult;
import org.synanton.extraction.spi.spi.model.ExtractionRequest;
import org.synanton.extraction.adapter.document.text.TextModalityAdapter;
import org.synanton.extraction.spi.spi.port.ModalityAdapter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionRouterTest {

    @Test
    void shouldPreferTextAdapterOverStub() {
        ModalityAdapter stub = new ModalityAdapter() {
            @Override
            public boolean supports(String mediaType) {
                return true;
            }

            @Override
            public String processorId() {
                return "audio-stub";
            }

            @Override
            public AdapterResult extract(
                    ExtractionRequest request,
                    java.io.InputStream source) {
                return AdapterResult.unsupported(request.mediaType());
            }
        };
        ExtractionRouter router = new ExtractionRouter(List.of(stub, new TextModalityAdapter()));
        ModalityAdapter adapter = router.route("text/markdown").orElseThrow();
        assertThat(adapter.processorId()).contains("text");
    }

    @Test
    void shouldReturnEmptyWhenNoAdapterMatches() {
        ExtractionRouter router = new ExtractionRouter(List.of(new TextModalityAdapter()));
        assertThat(router.route("application/zip")).isEmpty();
    }
}
