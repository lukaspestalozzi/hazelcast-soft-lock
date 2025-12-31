package com.github.reservation.oracle;

import com.github.reservation.AbstractReservationManagerTest;
import com.github.reservation.ReservationManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

/**
 * Tests for the JDBC/Oracle-backed ReservationManager implementation.
 * Uses H2 in-memory database for fast, isolated tests.
 */
class JdbcReservationManagerTest extends AbstractReservationManagerTest {

    private static HikariDataSource dataSource;
    private static final String TABLE_NAME = "RESERVATION_LOCKS";

    @BeforeAll
    static void setupDatabase() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:testdb-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(10);
        dataSource = new HikariDataSource(config);

        // Create table
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE RESERVATION_LOCKS (
                    reservation_key  VARCHAR(512) NOT NULL,
                    holder           VARCHAR(256) NOT NULL,
                    acquired_at      TIMESTAMP    NOT NULL,
                    expires_at       TIMESTAMP    NOT NULL,
                    PRIMARY KEY (reservation_key)
                )
                """);
        }
    }

    @AfterAll
    static void teardownDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Override
    protected ReservationManager createManager(Duration leaseTime) {
        return ReservationManager.oracle(dataSource)
            .leaseTime(leaseTime)
            .tableName(TABLE_NAME)
            .build();
    }

    @Override
    protected void cleanup() {
        // Clean table between tests
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM " + TABLE_NAME);
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    // ==================== JDBC-Specific Tests ====================

    @Test
    void shouldStoreHolderInfoInTable() throws Exception {
        var reservation = manager.getReservation("orders", "holder-test");
        reservation.lock();

        try {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT holder FROM RESERVATION_LOCKS WHERE reservation_key = 'orders::holder-test'")) {
                org.assertj.core.api.Assertions.assertThat(rs.next()).isTrue();
                String holder = rs.getString("holder");
                org.assertj.core.api.Assertions.assertThat(holder).contains("@");
            }
        } finally {
            reservation.unlock();
        }
    }

    @Test
    void shouldReturnCorrectTableName() {
        OracleReservationManager oracleManager = (OracleReservationManager) manager;
        org.assertj.core.api.Assertions.assertThat(oracleManager.getTableName()).isEqualTo(TABLE_NAME);
    }

    @Test
    void shouldCleanupExpiredLocks() throws Exception {
        // Create a lock that's already expired
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                INSERT INTO RESERVATION_LOCKS (reservation_key, holder, acquired_at, expires_at)
                VALUES ('orders::expired', 'old-holder', CURRENT_TIMESTAMP, DATEADD('SECOND', -10, CURRENT_TIMESTAMP))
                """);
        }

        // Trying to acquire should succeed because the old lock is expired
        var reservation = manager.getReservation("orders", "expired");
        org.assertj.core.api.Assertions.assertThat(reservation.tryLock()).isTrue();
        reservation.unlock();
    }
}
