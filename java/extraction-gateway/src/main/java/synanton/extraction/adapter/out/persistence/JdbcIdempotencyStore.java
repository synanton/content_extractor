package synanton.extraction.adapter.out.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import synanton.extraction.spi.port.IdempotencyStore;

import java.sql.ResultSet;
import java.util.Optional;

@Repository
public class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcIdempotencyStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Entry> findEntry(String tenantId, String idempotencyKey) {
        try {
            return jdbcTemplate.query(
                    """
                    SELECT operation_id, request_hash
                      FROM extraction_idempotency
                     WHERE tenant_id = ? AND idempotency_key = ?
                    """,
                    entryRowMapper(),
                    tenantId,
                    idempotencyKey
            ).stream().findFirst();
        } catch (RuntimeException e) {
            throw new IdempotencyStoreUnavailableException(
                    "Idempotency store unavailable for tenant=" + tenantId, e);
        }
    }

    @Override
    public void store(String tenantId, String idempotencyKey, String requestHash, String operationId) {
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO extraction_idempotency (
                        tenant_id, idempotency_key, request_hash, operation_id
                    ) VALUES (?, ?, ?, ?)
                    """,
                    tenantId,
                    idempotencyKey,
                    requestHash,
                    operationId
            );
        } catch (DuplicateKeyException e) {
            throw new DuplicateIdempotencyKeyException(
                    "Idempotency key already exists: " + idempotencyKey, e);
        } catch (RuntimeException e) {
            throw new IdempotencyStoreUnavailableException(
                    "Failed to store idempotency record for tenant=" + tenantId, e);
        }
    }

    private static RowMapper<Entry> entryRowMapper() {
        return (ResultSet rs, int rowNum) -> new Entry(
                rs.getString("operation_id"),
                rs.getString("request_hash")
        );
    }

    public static class IdempotencyStoreUnavailableException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public IdempotencyStoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class DuplicateIdempotencyKeyException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public DuplicateIdempotencyKeyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
