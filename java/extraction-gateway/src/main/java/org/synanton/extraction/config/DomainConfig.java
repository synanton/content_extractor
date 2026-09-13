package org.synanton.extraction.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.synanton.extraction.domain.port.ExtractionMetricsPort;
import org.synanton.extraction.domain.service.CancelOperationService;
import org.synanton.extraction.domain.service.CapacityService;
import org.synanton.extraction.domain.service.ExtractSyncService;
import org.synanton.extraction.domain.service.ExtractionRouter;
import org.synanton.extraction.domain.service.OperationQueryService;
import org.synanton.extraction.domain.service.RequestCanonicalizer;
import org.synanton.extraction.domain.service.SubmitExtractionService;
import org.synanton.extraction.spi.port.IdempotencyStore;
import org.synanton.extraction.spi.port.ModalityAdapter;
import org.synanton.extraction.spi.port.OperationRepository;
import org.synanton.extraction.spi.port.SourceObjectReader;

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
                idempotencyStore,
                operationRepository,
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
