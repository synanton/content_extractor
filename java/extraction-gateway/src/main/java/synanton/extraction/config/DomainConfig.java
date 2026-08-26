package synanton.extraction.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import synanton.extraction.domain.port.ExtractionMetricsPort;
import synanton.extraction.domain.service.CancelOperationService;
import synanton.extraction.domain.service.CapacityService;
import synanton.extraction.domain.service.ExtractSyncService;
import synanton.extraction.domain.service.ExtractionRouter;
import synanton.extraction.domain.service.OperationQueryService;
import synanton.extraction.domain.service.RequestCanonicalizer;
import synanton.extraction.domain.service.SubmitExtractionService;
import synanton.extraction.spi.port.IdempotencyStore;
import synanton.extraction.spi.port.ModalityAdapter;
import synanton.extraction.spi.port.OperationRepository;
import synanton.extraction.spi.port.SourceObjectReader;

import java.util.List;

@Configuration
public class DomainConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public ExtractionRouter extractionRouter(List<ModalityAdapter> adapters) {
        return new ExtractionRouter(adapters);
    }

    @Bean
    public ExtractSyncService extractSyncService(
            ExtractionRouter router,
            SourceObjectReader sourceObjectReader,
            ExtractionGatewayProperties properties,
            ExtractionMetricsPort metrics) {
        return new ExtractSyncService(router, sourceObjectReader, properties, metrics);
    }

    @Bean
    public RequestCanonicalizer requestCanonicalizer() {
        return new RequestCanonicalizer();
    }

    @Bean
    public SubmitExtractionService submitExtractionService(
            OperationRepository operationRepository,
            IdempotencyStore idempotencyStore,
            RequestCanonicalizer requestCanonicalizer,
            ExtractionGatewayProperties properties) {
        return new SubmitExtractionService(
                operationRepository,
                idempotencyStore,
                requestCanonicalizer,
                properties);
    }

    @Bean
    public OperationQueryService operationQueryService(OperationRepository operationRepository) {
        return new OperationQueryService(operationRepository);
    }

    @Bean
    public CancelOperationService cancelOperationService(OperationRepository operationRepository) {
        return new CancelOperationService(operationRepository);
    }

    @Bean
    public CapacityService capacityService(
            OperationRepository operationRepository,
            ExtractionRouter extractionRouter,
            ExtractionGatewayProperties properties) {
        return new CapacityService(operationRepository, extractionRouter, properties);
    }
}
