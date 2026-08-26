package synanton.extraction.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import synanton.extraction.adapter.document.text.TextModalityAdapter;
import synanton.extraction.adapter.in.grpc.ExtractionGrpcAdapter;
import synanton.extraction.adapter.out.metrics.MicrometerExtractionMetrics;
import synanton.extraction.adapter.out.objectstore.InMemorySourceObjectReader;
import synanton.extraction.adapter.out.persistence.JdbcIdempotencyStore;
import synanton.extraction.adapter.out.persistence.JdbcOperationRepository;
import synanton.extraction.adapter.out.persistence.JdbcResultStore;
import synanton.extraction.adapter.out.persistence.OperationAdmissionExecutor;
import synanton.extraction.adapter.out.worker.ExtractionWorker;
import synanton.extraction.config.ExtractionGatewayProperties;
import synanton.extraction.domain.service.CancelOperationService;
import synanton.extraction.domain.service.CapacityService;
import synanton.extraction.domain.service.ExtractSyncService;
import synanton.extraction.domain.service.ExtractionRouter;
import synanton.extraction.domain.service.OperationQueryService;
import synanton.extraction.domain.service.RequestCanonicalizer;
import synanton.extraction.domain.service.SubmitExtractionService;
import synanton.extraction.v1.ExtractionOperation;
import synanton.extraction.v1.ExtractionRequestItem;
import synanton.extraction.v1.ExtractionResult;
import synanton.extraction.v1.ExtractionServiceGrpc;
import synanton.extraction.v1.ExtractionStatus;
import synanton.extraction.v1.GetOperationsRequest;
import synanton.extraction.v1.GetResultRequest;
import synanton.extraction.v1.ObjectReference;
import synanton.extraction.v1.PriorityClass;
import synanton.extraction.v1.SubmitExtractionRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Disabled("Requires Docker; run manually with Testcontainers")
class AsyncExtractionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private Server server;
    private ManagedChannel channel;
    private ExtractionServiceGrpc.ExtractionServiceBlockingStub client;
    private ExtractionWorker worker;
    private InMemorySourceObjectReader objectStore;

    @BeforeEach
    void setUp() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        ObjectMapper objectMapper = new ObjectMapper();
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        JdbcOperationRepository operationRepository = new JdbcOperationRepository(jdbcTemplate, objectMapper);
        JdbcIdempotencyStore idempotencyStore = new JdbcIdempotencyStore(jdbcTemplate);
        JdbcResultStore resultStore = new JdbcResultStore(jdbcTemplate, objectMapper);

        ExtractionGatewayProperties properties = new ExtractionGatewayProperties();
        properties.getAsync().setWorkerPollIntervalMs(50);
        properties.getAsync().setMaxConcurrentOperationsPerTenant(5);

        objectStore = new InMemorySourceObjectReader();
        ExtractSyncService extractSyncService = new ExtractSyncService(
                new ExtractionRouter(List.of(new TextModalityAdapter())),
                objectStore,
                properties,
                new MicrometerExtractionMetrics(new SimpleMeterRegistry()));

        SubmitExtractionService submitExtractionService = new SubmitExtractionService(
                operationRepository,
                idempotencyStore,
                new RequestCanonicalizer(),
                properties);
        OperationAdmissionExecutor admissionExecutor = new OperationAdmissionExecutor(
                submitExtractionService,
                new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)));

        ExtractionGrpcAdapter adapter = new ExtractionGrpcAdapter(
                extractSyncService,
                admissionExecutor,
                new OperationQueryService(operationRepository),
                new CancelOperationService(operationRepository),
                new CapacityService(operationRepository, extractSyncService.router(), properties),
                operationRepository,
                resultStore,
                properties);

        worker = new ExtractionWorker(
                operationRepository,
                extractSyncService,
                resultStore,
                properties);
        worker.start();

        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(adapter).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = ExtractionServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
        worker.stop();
        channel.shutdownNow();
        server.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
        server.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldSubmitProcessAndFetchAsyncResult() throws Exception {
        byte[] body = "Async extraction body".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        objectStore.put("tenant-content", "docs/note.txt", body);

        SubmitExtractionRequest request = SubmitExtractionRequest.newBuilder()
                .setTenantId("tenant-a")
                .setIdempotencyKey("async-job-1")
                .setPriorityClass(PriorityClass.PRIORITY_NORMAL)
                .setItem(ExtractionRequestItem.newBuilder()
                        .setContentRefId("content-async-1")
                        .setMediaType("text/plain")
                        .setSource(ObjectReference.newBuilder()
                                .setBucket("tenant-content")
                                .setKey("docs/note.txt")
                                .setSha256(sha)
                                .setSizeBytes(body.length))
                        .build())
                .build();

        ExtractionOperation submitted = client.submitExtraction(request);
        assertThat(submitted.getStatus()).isEqualTo(ExtractionStatus.STATUS_QUEUED);

        ExtractionStatus terminalStatus = ExtractionStatus.STATUS_UNSPECIFIED;
        for (int attempt = 0; attempt < 100; attempt++) {
            ExtractionOperation polled = client.getOperations(GetOperationsRequest.newBuilder()
                    .setTenantId("tenant-a")
                    .addOperationIds(submitted.getOperationId())
                    .build()).getOperations(0);
            terminalStatus = polled.getStatus();
            if (terminalStatus == ExtractionStatus.STATUS_COMPLETED) {
                break;
            }
            Thread.sleep(100);
        }

        assertThat(terminalStatus).isEqualTo(ExtractionStatus.STATUS_COMPLETED);

        ExtractionResult result = client.getResult(GetResultRequest.newBuilder()
                .setTenantId("tenant-a")
                .setOperationId(submitted.getOperationId())
                .build());
        assertThat(result.getFlattenedText()).contains("Async extraction body");
    }
}
