package org.synanton.extraction.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.synanton.extraction.adapter.document.pdf.OpenDataLoaderClient;
import org.synanton.extraction.adapter.document.pdf.PdfModalityAdapter;
import org.synanton.extraction.adapter.document.text.TextModalityAdapter;
import org.synanton.extraction.adapter.in.grpc.ExtractionGrpcAdapter;
import org.synanton.extraction.adapter.out.metrics.MicrometerExtractionMetrics;
import org.synanton.extraction.adapter.out.objectstore.InMemorySourceObjectReader;
import org.synanton.extraction.adapter.out.persistence.JdbcIdempotencyStore;
import org.synanton.extraction.adapter.out.persistence.JdbcOperationRepository;
import org.synanton.extraction.adapter.out.persistence.JdbcResultStore;
import org.synanton.extraction.adapter.out.persistence.OperationAdmissionExecutor;
import org.synanton.extraction.adapter.out.worker.ExtractionWorker;
import org.synanton.extraction.config.ExtractionGatewayProperties;
import org.synanton.extraction.domain.service.CancelOperationService;
import org.synanton.extraction.domain.service.CapacityService;
import org.synanton.extraction.domain.service.ExtractSyncService;
import org.synanton.extraction.domain.service.ExtractionRouter;
import org.synanton.extraction.domain.service.OperationPersistence;
import org.synanton.extraction.domain.service.OperationQueryService;
import org.synanton.extraction.domain.service.RequestCanonicalizer;
import org.synanton.extraction.domain.service.SubmitExtractionService;
import org.synanton.extraction.v1.ExtractionOperation;
import org.synanton.extraction.v1.ExtractionRequestItem;
import org.synanton.extraction.v1.ExtractionResult;
import org.synanton.extraction.v1.ExtractionServiceGrpc;
import org.synanton.extraction.v1.ExtractionStatus;
import org.synanton.extraction.v1.GetOperationsRequest;
import org.synanton.extraction.v1.GetResultRequest;
import org.synanton.extraction.v1.ObjectReference;
import org.synanton.extraction.v1.PriorityClass;
import org.synanton.extraction.v1.SubmitExtractionRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AsyncExtractionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // Any instance works — OpenDataLoaderPDF.shutdown() is a static, JVM-wide shutdown of
    // the library's shared resources; extraction-gateway has no direct dependency on
    // opendataloader-pdf-core itself (an implementation-only dependency of adapter-document-pdf),
    // so this goes through PdfModalityAdapter's own public shutdown() rather than importing it.
    private static PdfModalityAdapter pdfAdapterForShutdown;

    @AfterAll
    static void shutdownOpenDataLoader() {
        if (pdfAdapterForShutdown != null) {
            pdfAdapterForShutdown.shutdown();
        }
    }

    private Server server;
    private ManagedChannel channel;
    private ExtractionServiceGrpc.ExtractionServiceBlockingStub client;
    private ExtractionWorker worker;
    private InMemorySourceObjectReader objectStore;
    private JdbcTemplate jdbcTemplate;

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
        jdbcTemplate = new JdbcTemplate(dataSource);

        JdbcOperationRepository operationRepository = new JdbcOperationRepository(jdbcTemplate, objectMapper);
        JdbcIdempotencyStore idempotencyStore = new JdbcIdempotencyStore(jdbcTemplate);
        JdbcResultStore resultStore = new JdbcResultStore(jdbcTemplate, objectMapper);
        OperationPersistence operationPersistence =
                new OperationPersistence(operationRepository, resultStore, idempotencyStore);

        ExtractionGatewayProperties properties = new ExtractionGatewayProperties();
        properties.getAsync().setWorkerPollIntervalMs(50);
        properties.getAsync().setMaxConcurrentOperationsPerTenant(5);

        objectStore = new InMemorySourceObjectReader();
        PdfModalityAdapter pdfAdapter = new PdfModalityAdapter(new OpenDataLoaderClient(objectMapper));
        pdfAdapterForShutdown = pdfAdapter;
        ExtractSyncService extractSyncService = new ExtractSyncService(
                new ExtractionRouter(List.of(new TextModalityAdapter(), pdfAdapter)),
                objectStore,
                properties,
                new MicrometerExtractionMetrics(new SimpleMeterRegistry()));

        TransactionTemplate transactionTemplate = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        SubmitExtractionService submitExtractionService = new SubmitExtractionService(
                idempotencyStore,
                operationRepository,
                new RequestCanonicalizer(),
                operationPersistence,
                properties,
                () -> Instant.now(Clock.systemUTC()));
        OperationAdmissionExecutor admissionExecutor = new OperationAdmissionExecutor(
                submitExtractionService,
                transactionTemplate);

        ExtractionGrpcAdapter adapter = new ExtractionGrpcAdapter(
                extractSyncService,
                admissionExecutor,
                new OperationQueryService(operationRepository),
                new CancelOperationService(operationRepository),
                new CapacityService(operationRepository, extractSyncService.router(), properties),
                operationRepository,
                resultStore,
                operationPersistence,
                idempotencyStore,
                new RequestCanonicalizer(),
                transactionTemplate,
                properties);

        worker = new ExtractionWorker(
                operationRepository,
                extractSyncService,
                operationPersistence,
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

    @Test
    void shouldSubmitProcessAndFetchAsyncResultForPdf() throws Exception {
        byte[] body = Files.readAllBytes(Path.of("src/test/resources/fixtures/quarterly-report.pdf"));
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        objectStore.put("tenant-content", "docs/quarterly-report.pdf", body);

        SubmitExtractionRequest request = SubmitExtractionRequest.newBuilder()
                .setTenantId("tenant-a")
                .setIdempotencyKey("async-pdf-job-1")
                .setPriorityClass(PriorityClass.PRIORITY_NORMAL)
                .setItem(ExtractionRequestItem.newBuilder()
                        .setContentRefId("content-async-pdf-1")
                        .setMediaType("application/pdf")
                        .setSource(ObjectReference.newBuilder()
                                .setBucket("tenant-content")
                                .setKey("docs/quarterly-report.pdf")
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
        assertThat(result.getFlattenedText()).contains("Quarterly Report");
    }

    @Test
    void shouldPersistSyncExtractionOperationResultAndIdempotency() throws Exception {
        byte[] body = "Sync extraction body".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(body);
        objectStore.put("tenant-content", "docs/sync.txt", body);

        ExtractionResult result = client.extractSync(syncRequest(
                "tenant-a", "sync-job-1", "content-sync-1", "docs/sync.txt", body, sha));

        assertThat(result.getStatus()).isEqualTo(ExtractionStatus.STATUS_COMPLETED);
        assertThat(result.getOperationId()).isNotBlank();
        assertThat(count("extraction_operations", "tenant_id = ? AND operation_id = ?",
                "tenant-a", result.getOperationId())).isEqualTo(1);
        assertThat(count("extraction_results", "tenant_id = ? AND operation_id = ?",
                "tenant-a", result.getOperationId())).isEqualTo(1);
        assertThat(count("extraction_idempotency", "tenant_id = ? AND idempotency_key = ?",
                "tenant-a", "sync-job-1")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM extraction_operations WHERE operation_id = ?",
                String.class, result.getOperationId())).isEqualTo("COMPLETED");
    }

    @Test
    void shouldReturnExistingOperationForDuplicateSyncKey() throws Exception {
        byte[] body = "Duplicate sync body".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(body);
        objectStore.put("tenant-content", "docs/sync-duplicate.txt", body);
        SubmitExtractionRequest request = syncRequest(
                "tenant-a", "sync-duplicate", "content-sync-duplicate", "docs/sync-duplicate.txt", body, sha);

        ExtractionResult first = client.extractSync(request);
        ExtractionResult second = client.extractSync(request);

        assertThat(second.getOperationId()).isEqualTo(first.getOperationId());
        assertThat(count("extraction_operations", "tenant_id = ? AND idempotency_key = ?",
                "tenant-a", "sync-duplicate")).isEqualTo(1);
        assertThat(count("extraction_results", "tenant_id = ? AND operation_id = ?",
                "tenant-a", first.getOperationId())).isEqualTo(1);
    }

    @Test
    void shouldPersistFailedSyncExtraction() throws Exception {
        byte[] body = "Missing object body".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(body);

        ExtractionResult result = client.extractSync(syncRequest(
                "tenant-a", "sync-missing", "content-sync-missing", "docs/missing.txt", body, sha));

        assertThat(result.getStatus()).isEqualTo(ExtractionStatus.STATUS_FAILED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM extraction_operations WHERE operation_id = ?",
                String.class, result.getOperationId())).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_code FROM extraction_operation_items WHERE operation_id = ?",
                String.class, result.getOperationId())).isNotBlank();
        assertThat(count("extraction_results", "tenant_id = ? AND operation_id = ?",
                "tenant-a", result.getOperationId())).isEqualTo(1);
    }

    private int count(String table, String where, Object... args) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + where,
                Integer.class,
                args);
        return count == null ? 0 : count;
    }

    private static SubmitExtractionRequest syncRequest(
            String tenantId,
            String idempotencyKey,
            String contentRefId,
            String objectKey,
            byte[] body,
            String sha) {
        return SubmitExtractionRequest.newBuilder()
                .setTenantId(tenantId)
                .setIdempotencyKey(idempotencyKey)
                .setPriorityClass(PriorityClass.PRIORITY_NORMAL)
                .setItem(ExtractionRequestItem.newBuilder()
                        .setContentRefId(contentRefId)
                        .setMediaType("text/plain")
                        .setSource(ObjectReference.newBuilder()
                                .setBucket("tenant-content")
                                .setKey(objectKey)
                                .setSha256(sha)
                                .setSizeBytes(body.length))
                        .build())
                .build();
    }

    private static String sha256(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }
}
