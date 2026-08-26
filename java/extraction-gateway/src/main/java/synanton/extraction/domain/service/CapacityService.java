package synanton.extraction.domain.service;

import synanton.extraction.config.ExtractionGatewayProperties;
import synanton.extraction.domain.model.ExtractionItemCommand;
import synanton.extraction.domain.model.SubmitExtractionCommand;
import synanton.extraction.spi.port.ModalityAdapter;
import synanton.extraction.spi.port.OperationRepository;

public class CapacityService {

    public enum Level {
        AVAILABLE,
        LIMITED,
        SATURATED
    }

    public record CapacitySnapshot(Level level, boolean acceptingWork, Long estimatedQueueDelaySeconds) {
    }

    private final OperationRepository operationRepository;
    private final ExtractionRouter extractionRouter;
    private final ExtractionGatewayProperties properties;

    public CapacityService(
            OperationRepository operationRepository,
            ExtractionRouter extractionRouter,
            ExtractionGatewayProperties properties) {
        this.operationRepository = operationRepository;
        this.extractionRouter = extractionRouter;
        this.properties = properties;
    }

    public CapacitySnapshot getCapacity(String tenantId, String mediaType) {
        int maxConcurrent = properties.getAsync().getMaxConcurrentOperationsPerTenant();
        int active = operationRepository.countActiveOperations(tenantId);
        if (active >= maxConcurrent) {
            return new CapacitySnapshot(Level.SATURATED, false, null);
        }
        if (active >= Math.max(1, maxConcurrent * 0.8)) {
            return new CapacitySnapshot(Level.LIMITED, true, (long) active);
        }
        if (mediaType != null && !mediaType.isBlank() && extractionRouter.route(mediaType).isEmpty()) {
            return new CapacitySnapshot(Level.AVAILABLE, false, null);
        }
        return new CapacitySnapshot(Level.AVAILABLE, true, null);
    }

    public EstimateExtractionService.Estimate estimate(SubmitExtractionCommand command) {
        if (command.items().isEmpty()) {
            return EstimateExtractionService.Estimate.invalid("No items to estimate");
        }
        ExtractionItemCommand item = command.items().getFirst();
        if (extractionRouter.route(item.mediaType()).isEmpty()) {
            return EstimateExtractionService.Estimate.unsupported(item.mediaType());
        }
        long sizeBytes = item.source().sizeBytes();
        long estimatedPayload = Math.min(sizeBytes / 4, properties.getLimits().getMaxPayloadBytes());
        long estimatedDuration = Math.max(1L, sizeBytes / 1_048_576L);
        return EstimateExtractionService.Estimate.processable(estimatedDuration, estimatedPayload);
    }
}
