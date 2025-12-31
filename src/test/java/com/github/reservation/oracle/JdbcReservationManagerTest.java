package com.github.reservation.oracle;

import com.github.reservation.AbstractReservationManagerTest;
import com.github.reservation.ReservationManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    protected ReservationManager createManager(String domain, Duration leaseTime) {
        return ReservationManager.oracle(dataSource)
            .domain(domain)
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
        var reservation = manager.getReservation("holder-test");
        reservation.lock();

        try {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT holder FROM RESERVATION_LOCKS WHERE reservation_key = 'orders::holder-test'")) {
                assertThat(rs.next()).isTrue();
                String holder = rs.getString("holder");
                assertThat(holder).contains("@");
            }
        } finally {
            reservation.unlock();
        }
    }

    @Test
    void shouldReturnCorrectTableName() {
        OracleReservationManager oracleManager = (OracleReservationManager) manager;
        assertThat(oracleManager.getTableName()).isEqualTo(TABLE_NAME);
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
        var reservation = manager.getReservation("expired");
        assertThat(reservation.tryLock()).isTrue();
        reservation.unlock();
    }

    @Test
    void shouldIsolateBetweenDomains() throws Exception {
        ReservationManager manager1 = ReservationManager.oracle(dataSource)
            .domain("domain1")
            .tableName(TABLE_NAME)
            .build();
        ReservationManager manager2 = ReservationManager.oracle(dataSource)
            .domain("domain2")
            .tableName(TABLE_NAME)
            .build();

        try {
            // Lock same identifier in domain1
            var res1 = manager1.getReservation("shared-id");
            res1.lock();

            // Should be able to lock same identifier in domain2 (different composite key)
            var res2 = manager2.getReservation("shared-id");
            assertThat(res2.tryLock()).isTrue();
            res2.unlock();

            res1.unlock();
        } finally {
            manager1.close();
            manager2.close();
        }
    }

    @Test
    void builderShouldRequireDomain() {
        assertThatThrownBy(() ->
            ReservationManager.oracle(dataSource).build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("domain");
    }
}
