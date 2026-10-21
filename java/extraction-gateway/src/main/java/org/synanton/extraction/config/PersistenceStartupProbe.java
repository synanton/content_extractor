package org.synanton.extraction.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-shot startup line so persistence failures are visible without a debug
 * session. Runs after Flyway, so {@code tables=missing} means the migration did
 * not run rather than "not checked yet".
 */
@Component
public class PersistenceStartupProbe implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(PersistenceStartupProbe.class);

    private final JdbcTemplate jdbcTemplate;
    private final String datasourceUrl;

    public PersistenceStartupProbe(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            Boolean present = jdbcTemplate.queryForObject(
                    "SELECT to_regclass('public.extraction_operations') IS NOT NULL"
                            + " AND to_regclass('public.extraction_results') IS NOT NULL"
                            + " AND to_regclass('public.extraction_idempotency') IS NOT NULL",
                    Boolean.class);
            log.info(
                    "extraction.persistence.enabled=true db={} tables={}",
                    redact(datasourceUrl),
                    Boolean.TRUE.equals(present) ? "present" : "missing");
        } catch (RuntimeException failure) {
            log.warn(
                    "extraction.persistence.enabled=true db={} tables=unknown error={}",
                    redact(datasourceUrl),
                    failure.getMessage());
        }
    }

    private static String redact(String url) {
        if (url == null || url.isBlank()) {
            return "<unset>";
        }
        return url.replaceAll("(?i)(password=)[^&]*", "$1***");
    }
}
