package synanton.extraction.domain.service;

import org.junit.jupiter.api.Test;
import synanton.extraction.adapter.document.text.TextModalityAdapter;
import synanton.extraction.spi.port.ModalityAdapter;

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
            public synanton.extraction.spi.model.AdapterResult extract(
                    synanton.extraction.spi.model.ExtractionRequest request,
                    java.io.InputStream source) {
                return synanton.extraction.spi.model.AdapterResult.unsupported(request.mediaType());
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
