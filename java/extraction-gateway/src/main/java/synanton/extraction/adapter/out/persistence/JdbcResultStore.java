package synanton.extraction.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import synanton.extraction.spi.model.AdapterResult;
import synanton.extraction.spi.model.ExtractionFailure;
import synanton.extraction.spi.model.FeatureOutcome;
import synanton.extraction.spi.model.NormalizedDocument;
import synanton.extraction.spi.port.ResultStore;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcResultStore implements ResultStore {

    private static final TypeReference<Map<String, FeatureOutcome>> FEATURE_MAP =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcResultStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void store(String tenantId, String operationId, int itemIndex, AdapterResult result) {
        store(tenantId, operationId, itemIndex, result, null, null);
    }

    public void store(
            String tenantId,
            String operationId,
            int itemIndex,
            AdapterResult result,
            String processorId,
            String sourceSha256) {
        StoredResult stored = StoredResult.from(result, processorId, sourceSha256);
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO extraction_results (
                        tenant_id, operation_id, item_index, result_json, processor_id, source_sha256
                    ) VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?)
                    ON CONFLICT (tenant_id, operation_id, item_index) DO UPDATE
                       SET result_json = EXCLUDED.result_json,
                           processor_id = EXCLUDED.processor_id,
                           source_sha256 = EXCLUDED.source_sha256
                    """,
                    tenantId,
                    operationId,
                    itemIndex,
                    objectMapper.writeValueAsString(stored),
                    processorId,
                    sourceSha256
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize extraction result", e);
        }
    }

    @Override
    public Optional<AdapterResult> load(String tenantId, String operationId, int itemIndex) {
        List<AdapterResult> rows = jdbcTemplate.query(
                """
                SELECT result_json
                  FROM extraction_results
                 WHERE tenant_id = ? AND operation_id = ? AND item_index = ?
                """,
                (rs, rowNum) -> mapStoredResult(rs).orElseThrow(),
                tenantId,
                operationId,
                itemIndex
        );
        return rows.stream().findFirst();
    }

    private Optional<AdapterResult> mapStoredResult(ResultSet rs) throws SQLException {
        try {
            StoredResult stored = objectMapper.readValue(rs.getString("result_json"), StoredResult.class);
            return Optional.of(stored.toAdapterResult());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize extraction result", e);
        }
    }

    record StoredResult(
            NormalizedDocument document,
            Map<String, FeatureOutcome> featureStates,
            ExtractionFailure failure,
            String processorId,
            String sourceSha256) {

        static StoredResult from(AdapterResult result) {
            return from(result, null, null);
        }

        static StoredResult from(AdapterResult result, String processorId, String sourceSha256) {
            return new StoredResult(
                    result.document(),
                    result.featureStates(),
                    result.failure(),
                    processorId,
                    sourceSha256
            );
        }

        AdapterResult toAdapterResult() {
            if (failure != null) {
                return AdapterResult.failed(failure, featureStates == null ? Map.of() : featureStates);
            }
            return AdapterResult.success(document, featureStates == null ? Map.of() : featureStates);
        }
    }
}
