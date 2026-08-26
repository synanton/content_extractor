package synanton.extraction.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import synanton.extraction.domain.service.ExtractSyncService;
import synanton.extraction.domain.service.ExtractionRouter;
import synanton.extraction.spi.port.ModalityAdapter;
import synanton.extraction.spi.port.SourceObjectReader;

import java.util.List;

@Configuration
public class DomainConfig {

    @Bean
    public ExtractionRouter extractionRouter(List<ModalityAdapter> adapters) {
        return new ExtractionRouter(adapters);
    }

    @Bean
    public ExtractSyncService extractSyncService(
            ExtractionRouter router,
            SourceObjectReader sourceObjectReader,
            ExtractionGatewayProperties properties) {
        return new ExtractSyncService(router, sourceObjectReader, properties);
    }
}
