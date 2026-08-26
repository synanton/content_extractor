package synanton.extraction.adapter.out.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import synanton.extraction.config.ExtractionGatewayProperties;
import synanton.extraction.domain.model.SyncExtractionOutcome;
import synanton.extraction.domain.model.SyncExtractionOutcome.OutcomeStatus;
import synanton.extraction.domain.service.ExtractSyncService;
import synanton.extraction.spi.model.ExtractionOptions;
import synanton.extraction.spi.model.NormalizedDocument;
import synanton.extraction.spi.model.ObjectRef;
import synanton.extraction.spi.model.OperationState;
import synanton.extraction.spi.port.OperationRepository;
import synanton.extraction.spi.port.OperationRepository.ExtractionOperation;
import synanton.extraction.spi.port.OperationRepository.ItemRecord;
import synanton.extraction.spi.port.OperationRepository.ItemSource;
import synanton.extraction.spi.port.ResultStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExtractionWorkerTest {

    @Mock
    private OperationRepository operationRepository;

    @Mock
    private ExtractSyncService extractSyncService;

    @Mock
    private ResultStore resultStore;

    private ExtractionWorker worker;

    @BeforeEach
    void setUp() {
        ExtractionGatewayProperties properties = new ExtractionGatewayProperties();
        properties.getAsync().setLeaseTimeoutSeconds(60);
        worker = new ExtractionWorker(
                operationRepository,
                extractSyncService,
                resultStore,
                properties);
    }

    @Test
    void shouldProcessClaimedOperationThroughExtractSyncPath() {
        ExtractionOperation operation = new ExtractionOperation(
                "op-1",
                "tenant-a",
                OperationState.RUNNING,
                "job-1",
                "PRIORITY_NORMAL",
                Instant.now(),
                Instant.now(),
                Instant.parse("2030-01-01T00:00:00Z"),
                "ADMISSION_ADMITTED",
                List.of(new ItemRecord(
                        0,
                        "content-1",
                        "text/plain",
                        OperationState.QUEUED,
                        Map.of(),
                        null,
                        null)));

        when(operationRepository.findById("op-1")).thenReturn(Optional.of(operation));
        when(operationRepository.findItemSource("op-1", 0)).thenReturn(Optional.of(
                new ItemSource("bucket", "key", null, "c".repeat(64), 32)));
        when(extractSyncService.extract(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                any(ObjectRef.class),
                anyString(),
                any(ExtractionOptions.class),
                any()))
                .thenReturn(new SyncExtractionOutcome(
                        "op-1",
                        "content-1",
                        OutcomeStatus.COMPLETED,
                        new NormalizedDocument("text/plain", Map.of(), List.of(), "hello"),
                        Map.of(),
                        null,
                        "text-adapter",
                        "c".repeat(64)));

        worker.processClaimedOperation("op-1");

        verify(operationRepository).transitionState("op-1", OperationState.RUNNING, OperationState.COMPLETED);
        verify(operationRepository).assignCompletionSequence("op-1");
        verify(resultStore).store(eq("tenant-a"), eq("op-1"), eq(0), any());
    }
}
