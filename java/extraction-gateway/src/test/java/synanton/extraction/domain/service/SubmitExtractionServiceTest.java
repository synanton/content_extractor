package synanton.extraction.domain.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import synanton.extraction.config.ExtractionGatewayProperties;
import synanton.extraction.domain.model.ExtractionItemCommand;
import synanton.extraction.domain.model.SubmitExtractionCommand;
import synanton.extraction.spi.model.ExtractionOptions;
import synanton.extraction.spi.model.ObjectRef;
import synanton.extraction.spi.model.OperationState;
import synanton.extraction.spi.port.IdempotencyStore;
import synanton.extraction.spi.port.OperationRepository;
import synanton.extraction.spi.port.OperationRepository.ExtractionOperation;
import synanton.extraction.spi.port.OperationRepository.ItemRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmitExtractionServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private OperationRepository operationRepository;

    @Mock
    private IdempotencyStore idempotencyStore;

    @Mock
    private RequestCanonicalizer requestCanonicalizer;

    @InjectMocks
    private SubmitExtractionService submitExtractionService;

    @Test
    void shouldReturnExistingOperationForMatchingIdempotencyRecord() {
        SubmitExtractionCommand command = sampleCommand();
        ExtractionOperation existing = queuedOperation("op-1", command);
        when(requestCanonicalizer.canonicalize(command)).thenReturn("hash-1");
        when(idempotencyStore.findEntry("tenant-a", "job-1"))
                .thenReturn(Optional.of(new IdempotencyStore.Entry("op-1", "hash-1")));
        when(operationRepository.findById("op-1")).thenReturn(Optional.of(existing));

        ExtractionOperation result = submitExtractionService.submitTransactional(command);

        assertThat(result).isEqualTo(existing);
        verify(operationRepository, never()).saveNewOperation(any(), any());
    }

    @Test
    void shouldRejectConflictingIdempotencyReuse() {
        SubmitExtractionCommand command = sampleCommand();
        when(requestCanonicalizer.canonicalize(command)).thenReturn("hash-new");
        when(idempotencyStore.findEntry("tenant-a", "job-1"))
                .thenReturn(Optional.of(new IdempotencyStore.Entry("op-1", "hash-old")));

        assertThatThrownBy(() -> submitExtractionService.submitTransactional(command))
                .isInstanceOf(SubmitExtractionService.IdempotencyConflictException.class);
    }

    @Test
    void shouldQueueNewOperationWhenCapacityAvailable() {
        SubmitExtractionService service = new SubmitExtractionService(
                operationRepository,
                idempotencyStore,
                requestCanonicalizer,
                properties(10),
                () -> FIXED_NOW);
        SubmitExtractionCommand command = sampleCommand();

        when(idempotencyStore.findEntry("tenant-a", "job-1")).thenReturn(Optional.empty());
        when(operationRepository.countActiveOperations("tenant-a")).thenReturn(0);

        ExtractionOperation result = service.admitWithinTransaction(command, "hash-1");

        assertThat(result.state()).isEqualTo(OperationState.QUEUED);
        assertThat(result.items()).hasSize(1);
        verify(operationRepository).saveNewOperation(eq(result), any());
        verify(idempotencyStore).store("tenant-a", "job-1", "hash-1", result.id());
    }

    @Test
    void shouldRejectWhenTenantCapacityIsSaturated() {
        SubmitExtractionService service = new SubmitExtractionService(
                operationRepository,
                idempotencyStore,
                requestCanonicalizer,
                properties(1),
                () -> FIXED_NOW);
        SubmitExtractionCommand command = sampleCommand();

        when(idempotencyStore.findEntry("tenant-a", "job-1")).thenReturn(Optional.empty());
        when(operationRepository.countActiveOperations("tenant-a")).thenReturn(1);

        assertThatThrownBy(() -> service.admitWithinTransaction(command, "hash-1"))
                .isInstanceOf(SubmitExtractionService.CapacityRejectedException.class);
    }

    private static SubmitExtractionCommand sampleCommand() {
        return new SubmitExtractionCommand(
                "tenant-a",
                "job-1",
                List.of(new ExtractionItemCommand(
                        "content-1",
                        new ObjectRef("bucket", "key", null, "b".repeat(64), 64),
                        "text/plain",
                        ExtractionOptions.defaults())),
                "PRIORITY_NORMAL",
                Instant.parse("2030-01-01T00:00:00Z"));
    }

    private static ExtractionOperation queuedOperation(String operationId, SubmitExtractionCommand command) {
        return new ExtractionOperation(
                operationId,
                command.tenantId(),
                OperationState.QUEUED,
                command.idempotencyKey(),
                command.priority(),
                FIXED_NOW,
                FIXED_NOW,
                command.expiresAt(),
                SubmitExtractionService.ADMISSION_ADMITTED,
                List.of(new ItemRecord(
                        0,
                        command.items().getFirst().contentRefId(),
                        command.items().getFirst().mediaType(),
                        OperationState.QUEUED,
                        java.util.Map.of(),
                        null,
                        null)));
    }

    private static ExtractionGatewayProperties properties(int maxConcurrent) {
        ExtractionGatewayProperties properties = new ExtractionGatewayProperties();
        properties.getAsync().setMaxConcurrentOperationsPerTenant(maxConcurrent);
        return properties;
    }
}
