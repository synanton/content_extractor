package synanton.extraction.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import synanton.extraction.spi.model.FeatureOutcome;
import synanton.extraction.spi.model.OperationState;
import synanton.extraction.spi.port.OperationRepository;
import synanton.extraction.spi.port.OperationRepository.ItemSource;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcOperationRepository implements OperationRepository {

    private static final String TERMINAL_STATES =
            "'COMPLETED','PARTIAL','FAILED','CANCELLED','EXPIRED'";

    private static final TypeReference<Map<String, FeatureOutcome>> FEATURE_MAP =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcOperationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(ExtractionOperation operation) {
        jdbcTemplate.update(
                """
                INSERT INTO extraction_operations (
                    operation_id, tenant_id, status, progress, priority_class, idempotency_key,
                    admission_verdict, created_at, updated_at, expires_at, error_code, error_diagnostic
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (operation_id) DO UPDATE
                   SET status = EXCLUDED.status,
                       progress = EXCLUDED.progress,
                       updated_at = EXCLUDED.updated_at,
                       error_code = EXCLUDED.error_code,
                       error_diagnostic = EXCLUDED.error_diagnostic
                """,
                operation.id(),
                operation.tenantId(),
                operation.state().name(),
                progressFor(operation),
                operation.priority(),
                operation.idempotencyKey(),
                operation.admissionVerdict(),
                toOffset(operation.createdAt()),
                toOffset(operation.updatedAt()),
                toOffset(operation.expiresAt()),
                null,
                null
        );

        for (ItemRecord item : operation.items()) {
            saveItem(operation.id(), item);
        }
    }

    private void saveItem(String operationId, ItemRecord item) {
        jdbcTemplate.update(
                """
                INSERT INTO extraction_operation_items (
                    operation_id, item_index, content_ref_id, media_type,
                    source_bucket, source_key, source_version, source_sha256, source_size,
                    status, progress, feature_states, error_code, error_diagnostic
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                ON CONFLICT (operation_id, item_index) DO UPDATE
                   SET status = EXCLUDED.status,
                       progress = EXCLUDED.progress,
                       feature_states = EXCLUDED.feature_states,
                       error_code = EXCLUDED.error_code,
                       error_diagnostic = EXCLUDED.error_diagnostic
                """,
                operationId,
                item.itemIndex(),
                item.contentRefId(),
                item.mediaType(),
                "",
                "",
                null,
                "",
                0L,
                item.state().name(),
                progressForItem(item),
                toJson(item.featureStates()),
                item.errorCode(),
                item.errorDiagnostic()
        );
    }

    @Override
    public void saveNewOperation(ExtractionOperation operation, List<ItemSource> itemSources) {
        jdbcTemplate.update(
                """
                INSERT INTO extraction_operations (
                    operation_id, tenant_id, status, progress, priority_class, idempotency_key,
                    admission_verdict, created_at, updated_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                operation.id(),
                operation.tenantId(),
                operation.state().name(),
                progressFor(operation),
                operation.priority(),
                operation.idempotencyKey(),
                operation.admissionVerdict(),
                toOffset(operation.createdAt()),
                toOffset(operation.updatedAt()),
                toOffset(operation.expiresAt())
        );

        for (int i = 0; i < operation.items().size(); i++) {
            ItemRecord item = operation.items().get(i);
            ItemSource source = itemSources.get(i);
            jdbcTemplate.update(
                    """
                    INSERT INTO extraction_operation_items (
                        operation_id, item_index, content_ref_id, media_type,
                        source_bucket, source_key, source_version, source_sha256, source_size,
                        status, progress, feature_states
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """,
                    operation.id(),
                    item.itemIndex(),
                    item.contentRefId(),
                    item.mediaType(),
                    source.bucket(),
                    source.key(),
                    source.version(),
                    source.sha256(),
                    source.sizeBytes(),
                    item.state().name(),
                    progressForItem(item),
                    toJson(item.featureStates())
            );
        }
    }

    @Override
    public Optional<ExtractionOperation> findById(String operationId) {
        List<ExtractionOperation> operations = jdbcTemplate.query(
                """
                SELECT operation_id, tenant_id, status, priority_class, idempotency_key,
                       admission_verdict, created_at, updated_at, expires_at
                  FROM extraction_operations
                 WHERE operation_id = ?
                """,
                operationHeaderMapper(),
                operationId
        );
        if (operations.isEmpty()) {
            return Optional.empty();
        }
        ExtractionOperation header = operations.getFirst();
        List<ItemRecord> items = loadItems(operationId);
        return Optional.of(rebuild(header, items));
    }

    @Override
    public List<ExtractionOperation> findByIds(String tenantId, List<String> operationIds) {
        if (operationIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", operationIds.stream().map(id -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(operationIds);

        List<ExtractionOperation> headers = jdbcTemplate.query(
                """
                SELECT operation_id, tenant_id, status, priority_class, idempotency_key,
                       admission_verdict, created_at, updated_at, expires_at
                  FROM extraction_operations
                 WHERE tenant_id = ? AND operation_id IN (%s)
                """.formatted(placeholders),
                operationHeaderMapper(),
                args.toArray()
        );

        List<ExtractionOperation> results = new ArrayList<>();
        for (ExtractionOperation header : headers) {
            results.add(rebuild(header, loadItems(header.id())));
        }
        return results;
    }

    @Override
    public boolean transitionState(String operationId, OperationState from, OperationState to) {
        guardTransition(from, to);
        int updated = jdbcTemplate.update(
                """
                UPDATE extraction_operations
                   SET status = ?, updated_at = NOW()
                 WHERE operation_id = ? AND status = ?
                """,
                to.name(),
                operationId,
                from.name()
        );
        return updated == 1;
    }

    @Override
    public Optional<String> claimNextQueued(Instant leasedUntil) {
        List<String> claimed = jdbcTemplate.query(
                """
                WITH next_job AS (
                    SELECT operation_id
                      FROM extraction_operations
                     WHERE status = 'QUEUED'
                       AND (leased_until IS NULL OR leased_until < NOW())
                     ORDER BY created_at
                     FOR UPDATE SKIP LOCKED
                     LIMIT 1
                )
                UPDATE extraction_operations AS operations
                   SET status = 'RUNNING',
                       leased_until = ?,
                       updated_at = NOW()
                  FROM next_job
                 WHERE operations.operation_id = next_job.operation_id
                RETURNING operations.operation_id
                """,
                (rs, rowNum) -> rs.getString("operation_id"),
                Timestamp.from(leasedUntil)
        );
        return claimed.stream().findFirst();
    }

    @Override
    public void refreshLease(String operationId, Instant leasedUntil) {
        jdbcTemplate.update(
                """
                UPDATE extraction_operations
                   SET leased_until = ?, updated_at = NOW()
                 WHERE operation_id = ? AND status = 'RUNNING'
                """,
                Timestamp.from(leasedUntil),
                operationId
        );
    }

    @Override
    public int countActiveOperations(String tenantId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM extraction_operations
                 WHERE tenant_id = ?
                   AND status NOT IN (%s)
                """.formatted(TERMINAL_STATES),
                Integer.class,
                tenantId
        );
        return count != null ? count : 0;
    }

    @Override
    public void acquireTenantAdmissionLock(String tenantId) {
        jdbcTemplate.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", tenantId);
    }

    @Override
    public void assignCompletionSequence(String operationId) {
        jdbcTemplate.update(
                """
                UPDATE extraction_operations
                   SET completion_seq = nextval('extraction_completion_seq'),
                       updated_at = NOW()
                 WHERE operation_id = ?
                   AND completion_seq IS NULL
                """,
                operationId
        );
    }

    @Override
    public void updateItemState(
            String operationId,
            int itemIndex,
            OperationState newState,
            Map<String, FeatureOutcome> featureStates,
            String errorCode,
            String errorDiagnostic) {
        jdbcTemplate.update(
                """
                UPDATE extraction_operation_items
                   SET status = ?,
                       progress = ?,
                       feature_states = CAST(? AS jsonb),
                       error_code = ?,
                       error_diagnostic = ?
                 WHERE operation_id = ? AND item_index = ?
                """,
                newState.name(),
                progressForTerminalItem(newState),
                toJson(featureStates),
                errorCode,
                errorDiagnostic,
                operationId,
                itemIndex
        );
    }

    @Override
    public List<ExtractionOperation> listCompleted(String tenantId, String cursor, int pageSize) {
        long afterSeq = cursor == null || cursor.isBlank() ? 0L : Long.parseLong(cursor);
        List<ExtractionOperation> headers = jdbcTemplate.query(
                """
                SELECT operation_id, tenant_id, status, priority_class, idempotency_key,
                       admission_verdict, created_at, updated_at, expires_at
                  FROM extraction_operations
                 WHERE tenant_id = ?
                   AND completion_seq IS NOT NULL
                   AND completion_seq > ?
                 ORDER BY completion_seq
                 LIMIT ?
                """,
                operationHeaderMapper(),
                tenantId,
                afterSeq,
                pageSize
        );

        List<ExtractionOperation> results = new ArrayList<>();
        for (ExtractionOperation header : headers) {
            results.add(rebuild(header, loadItems(header.id())));
        }
        return results;
    }

    @Override
    public Optional<ItemSource> findItemSource(String operationId, int itemIndex) {
        return jdbcTemplate.query(
                """
                SELECT source_bucket, source_key, source_version, source_sha256, source_size
                  FROM extraction_operation_items
                 WHERE operation_id = ? AND item_index = ?
                """,
                (rs, rowNum) -> new ItemSource(
                        rs.getString("source_bucket"),
                        rs.getString("source_key"),
                        rs.getString("source_version"),
                        rs.getString("source_sha256"),
                        rs.getLong("source_size")
                ),
                operationId,
                itemIndex
        ).stream().findFirst();
    }

    @Override
    public Optional<Long> findCompletionSequence(String operationId) {
        return jdbcTemplate.query(
                "SELECT completion_seq FROM extraction_operations WHERE operation_id = ?",
                (rs, rowNum) -> rs.getLong("completion_seq"),
                operationId
        ).stream().findFirst();
    }

    private List<ItemRecord> loadItems(String operationId) {
        return jdbcTemplate.query(
                """
                SELECT item_index, content_ref_id, media_type, status, feature_states,
                       error_code, error_diagnostic
                  FROM extraction_operation_items
                 WHERE operation_id = ?
                 ORDER BY item_index
                """,
                itemRowMapper(),
                operationId
        );
    }

    private static ExtractionOperation rebuild(ExtractionOperation header, List<ItemRecord> items) {
        return new ExtractionOperation(
                header.id(),
                header.tenantId(),
                header.state(),
                header.idempotencyKey(),
                header.priority(),
                header.createdAt(),
                header.updatedAt(),
                header.expiresAt(),
                header.admissionVerdict(),
                items
        );
    }

    private RowMapper<ExtractionOperation> operationHeaderMapper() {
        return (ResultSet rs, int rowNum) -> new ExtractionOperation(
                rs.getString("operation_id"),
                rs.getString("tenant_id"),
                OperationState.valueOf(rs.getString("status")),
                rs.getString("idempotency_key"),
                rs.getString("priority_class"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                toInstantNullable(rs.getTimestamp("expires_at")),
                rs.getString("admission_verdict"),
                List.of()
        );
    }

    private RowMapper<ItemRecord> itemRowMapper() {
        return (ResultSet rs, int rowNum) -> new ItemRecord(
                rs.getInt("item_index"),
                rs.getString("content_ref_id"),
                rs.getString("media_type"),
                OperationState.valueOf(rs.getString("status")),
                parseFeatureStates(rs.getString("feature_states")),
                rs.getString("error_code"),
                rs.getString("error_diagnostic")
        );
    }

    private Map<String, FeatureOutcome> parseFeatureStates(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> raw = objectMapper.readValue(json, new TypeReference<>() {
            });
            Map<String, FeatureOutcome> mapped = new HashMap<>();
            raw.forEach((key, value) -> mapped.put(key, FeatureOutcome.valueOf(value)));
            return Map.copyOf(mapped);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse feature states", e);
        }
    }

    private String toJson(Map<String, FeatureOutcome> featureStates) {
        try {
            Map<String, String> raw = new HashMap<>();
            featureStates.forEach((key, value) -> raw.put(key, value.name()));
            return objectMapper.writeValueAsString(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize feature states", e);
        }
    }

    private static void guardTransition(OperationState from, OperationState to) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalStateException("Invalid state transition: " + from + " → " + to);
        }
    }

    private static double progressFor(ExtractionOperation operation) {
        if (operation.items().isEmpty()) {
            return operation.state().isTerminal() ? 1.0 : 0.0;
        }
        return operation.items().stream()
                .mapToDouble(JdbcOperationRepository::progressForItem)
                .average()
                .orElse(0.0);
    }

    private static double progressForItem(ItemRecord item) {
        return progressForTerminalItem(item.state());
    }

    private static double progressForTerminalItem(OperationState state) {
        return switch (state) {
            case COMPLETED, PARTIAL, FAILED, CANCELLED, EXPIRED -> 1.0;
            case RUNNING -> 0.5;
            default -> 0.0;
        };
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Timestamp toOffsetNullable(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp.toInstant();
    }

    private static Instant toInstantNullable(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
