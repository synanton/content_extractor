package synanton.extraction.adapter.in.grpc;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import synanton.extraction.adapter.document.text.TextModalityAdapter;
import synanton.extraction.adapter.out.objectstore.InMemorySourceObjectReader;
import synanton.extraction.adapter.out.persistence.OperationAdmissionExecutor;
import synanton.extraction.config.ExtractionGatewayProperties;
import synanton.extraction.domain.service.CancelOperationService;
import synanton.extraction.domain.service.CapacityService;
import synanton.extraction.domain.service.ExtractSyncService;
import synanton.extraction.domain.service.ExtractionRouter;
import synanton.extraction.domain.service.OperationQueryService;
import synanton.extraction.spi.port.OperationRepository;
import synanton.extraction.spi.port.ResultStore;
import synanton.extraction.v1.DocumentPayload;
import synanton.extraction.v1.ExtractionResult;
import synanton.extraction.v1.ExtractionServiceGrpc;
import synanton.extraction.v1.ExtractionStatus;
import synanton.extraction.v1.ObjectReference;
import synanton.extraction.v1.PriorityClass;
import synanton.extraction.v1.SubmitExtractionRequest;
import synanton.extraction.v1.ExtractionRequestItem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ExtractionGrpcAdapterTest {

    @Mock
    private OperationAdmissionExecutor admissionExecutor;

    @Mock
    private OperationQueryService operationQueryService;

    @Mock
    private CancelOperationService cancelOperationService;

    @Mock
    private CapacityService capacityService;

    @Mock
    private OperationRepository operationRepository;

    @Mock
    private ResultStore resultStore;

    private Server server;
    private ManagedChannel channel;
    private ExtractionServiceGrpc.ExtractionServiceBlockingStub client;
    private InMemorySourceObjectReader store;

    @BeforeEach
    void start() throws Exception {
        store = new InMemorySourceObjectReader();
        ExtractSyncService service = new ExtractSyncService(
                new ExtractionRouter(List.of(new TextModalityAdapter())),
                store,
                new ExtractionGatewayProperties());
        ExtractionGrpcAdapter adapter = new ExtractionGrpcAdapter(
                service,
                admissionExecutor,
                operationQueryService,
                cancelOperationService,
                capacityService,
                operationRepository,
                resultStore,
                new ExtractionGatewayProperties());
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(adapter).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = ExtractionServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stop() throws Exception {
        channel.shutdownNow();
        server.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        server.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldExtractSyncPlainTextAndReturnInlinePayload() throws Exception {
        byte[] body = "Hello structured world".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        store.put("synanton-hot", "demo/a", body);

        ExtractionResult result = client.extractSync(SubmitExtractionRequest.newBuilder()
                .setTenantId("demo")
                .setIdempotencyKey("idem-1")
                .setPriorityClass(PriorityClass.PRIORITY_NORMAL)
                .setItem(ExtractionRequestItem.newBuilder()
                        .setContentRefId("ref-a")
                        .setMediaType("text/plain")
                        .setSource(ObjectReference.newBuilder()
                                .setBucket("synanton-hot")
                                .setKey("demo/a")
                                .setSha256(sha)
                                .setSizeBytes(body.length)
                                .build())
                        .build())
                .build());

        assertThat(result.getStatus()).isEqualTo(ExtractionStatus.STATUS_COMPLETED);
        assertThat(result.getFlattenedText()).contains("Hello structured world");
        assertThat(result.getPayload().hasInlineContent()).isTrue();
        DocumentPayload payload = DocumentPayload.parseFrom(result.getPayload().getInlineContent());
        assertThat(payload.getElementsCount()).isGreaterThan(0);
    }
}
