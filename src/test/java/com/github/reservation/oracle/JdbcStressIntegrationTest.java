package com.github.reservation.oracle;

import com.github.reservation.AbstractStressIntegrationTest;
import com.github.reservation.ReservationManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/**
 * JDBC stress tests using H2 in-memory database.
 *
 * <p>Runs all shared stress tests from {@link AbstractStressIntegrationTest}
 * against H2. For Oracle integration tests, use OracleStressIntegrationTest
 * with Testcontainers.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcStressIntegrationTest extends AbstractStressIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(JdbcStressIntegrationTest.class);
    private static final String TABLE_NAME = "RESERVATION_LOCKS";

    private HikariDataSource dataSource;

    @BeforeAll
    void setupDatabase() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:stresstest;DB_CLOSE_DELAY=-1");
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(5);
        config.setConnectionTimeout(5000);

        dataSource = new HikariDataSource(config);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS RESERVATION_LOCKS (
                    reservation_key  VARCHAR(512)  NOT NULL,
                    holder           VARCHAR(256)  NOT NULL,
                    acquired_at      TIMESTAMP     NOT NULL,
                    expires_at       TIMESTAMP     NOT NULL,
                    PRIMARY KEY (reservation_key)
                )
                """);
        }

        log.info("Database initialized with {} max connections", 20);
    }

    @AfterAll
    void teardownDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Override
    protected ReservationManager createManager(String domain, Duration leaseTime) {
        return ReservationManager.oracle(dataSource)
                .domain(domain)
                .leaseTime(leaseTime)
                .tableName(TABLE_NAME)
                .build();
    }

    @Override
    protected void cleanup() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM " + TABLE_NAME);
        } catch (SQLException e) {
            // Ignore cleanup errors
        }
    }
}
