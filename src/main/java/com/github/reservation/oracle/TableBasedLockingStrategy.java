package com.github.reservation.oracle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Default locking strategy using a custom table with TTL-based expiration.
 *
 * <p>This strategy uses optimistic locking: successful INSERT = lock acquired,
 * constraint violation = lock already held.</p>
 *
 * <p>Expired locks are cleaned up lazily on acquisition attempts.</p>
 *
 * <p>Required table schema:</p>
 * <pre>{@code
 * CREATE TABLE RESERVATION_LOCKS (
 *     reservation_key  VARCHAR(512)  NOT NULL,
 *     holder           VARCHAR(256)  NOT NULL,
 *     acquired_at      TIMESTAMP     NOT NULL,
 *     expires_at       TIMESTAMP     NOT NULL,
 *     PRIMARY KEY (reservation_key)
 * );
 * }</pre>
 */
public class TableBasedLockingStrategy implements LockingStrategy {

    private static final Logger log = LoggerFactory.getLogger(TableBasedLockingStrategy.class);

    private final DataSource dataSource;
    private final String tableName;

    // SQL statements
    private final String insertSql;
    private final String deleteSql;
    private final String deleteForceSql;
    private final String selectSql;
    private final String cleanupExpiredSql;
    private final String updateSql;

    public TableBasedLockingStrategy(DataSource dataSource, String tableName) {
        this.dataSource = dataSource;
        this.tableName = tableName;

        // Initialize SQL with table name
        // Use standard SQL that works with both Oracle and H2
        this.insertSql = String.format(
            "INSERT INTO %s (reservation_key, holder, acquired_at, expires_at) VALUES (?, ?, ?, ?)",
            tableName);
        this.deleteSql = String.format(
            "DELETE FROM %s WHERE reservation_key = ? AND holder = ?",
            tableName);
        this.deleteForceSql = String.format(
            "DELETE FROM %s WHERE reservation_key = ?",
            tableName);
        this.selectSql = String.format(
            "SELECT holder, acquired_at, expires_at FROM %s WHERE reservation_key = ? AND expires_at > ?",
            tableName);
        this.cleanupExpiredSql = String.format(
            "DELETE FROM %s WHERE reservation_key = ? AND expires_at <= ?",
            tableName);
        this.updateSql = String.format(
            "UPDATE %s SET holder = ?, acquired_at = ?, expires_at = ? WHERE reservation_key = ? AND holder = ?",
            tableName);
    }

    @Override
    public boolean tryAcquire(String reservationKey, String holder, Duration leaseTime) throws LockingException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Timestamp now = Timestamp.from(Instant.now());

                // First, clean up any expired lock for this key
                cleanupExpired(conn, reservationKey, now);

                // Try to insert new lock
                Timestamp expiresAt = Timestamp.from(Instant.now().plus(leaseTime));

                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, reservationKey);
                    ps.setString(2, holder);
                    ps.setTimestamp(3, now);
                    ps.setTimestamp(4, expiresAt);
                    ps.executeUpdate();
                }

                conn.commit();
                log.debug("Acquired lock: {} by {}", reservationKey, holder);
                return true;

            } catch (SQLException e) {
                conn.rollback();

                // Check if it's a unique constraint violation (lock already held)
                if (isUniqueConstraintViolation(e)) {
                    log.debug("Lock already held: {}", reservationKey);
                    return false;
                }

                throw e;
            }
        } catch (SQLException e) {
            throw new LockingException("Failed to acquire lock: " + reservationKey, e);
        }
    }

    @Override
    public boolean release(String reservationKey, String holder) throws LockingException {
        // Only release if the lock exists AND is not expired AND is owned by holder
        String releaseIfValidSql = String.format(
            "DELETE FROM %s WHERE reservation_key = ? AND holder = ? AND expires_at > ?",
            tableName);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(releaseIfValidSql)) {
            ps.setString(1, reservationKey);
            ps.setString(2, holder);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            int deleted = ps.executeUpdate();

            if (deleted > 0) {
                log.debug("Released lock: {} by {}", reservationKey, holder);
                return true;
            } else {
                log.debug("Lock not found, not owned, or expired: {} by {}", reservationKey, holder);
                return false;
            }
        } catch (SQLException e) {
            throw new LockingException("Failed to release lock: " + reservationKey, e);
        }
    }

    @Override
    public void forceRelease(String reservationKey) throws LockingException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(deleteForceSql)) {
            ps.setString(1, reservationKey);
            int deleted = ps.executeUpdate();
            log.info("Force released lock: {} (deleted {} rows)", reservationKey, deleted);
        } catch (SQLException e) {
            throw new LockingException("Failed to force release lock: " + reservationKey, e);
        }
    }

    @Override
    public boolean isLocked(String reservationKey) throws LockingException {
        return getLockInfo(reservationKey).isPresent();
    }

    @Override
    public Optional<LockInfo> getLockInfo(String reservationKey) throws LockingException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, reservationKey);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new LockInfo(
                        rs.getString("holder"),
                        rs.getTimestamp("acquired_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()
                    ));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new LockingException("Failed to get lock info: " + reservationKey, e);
        }
    }

    private void cleanupExpired(Connection conn, String reservationKey, Timestamp now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(cleanupExpiredSql)) {
            ps.setString(1, reservationKey);
            ps.setTimestamp(2, now);
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.debug("Cleaned up expired lock: {}", reservationKey);
            }
        }
    }

    private boolean isUniqueConstraintViolation(SQLException e) {
        // Check for various database-specific unique constraint violation codes
        String sqlState = e.getSQLState();
        int errorCode = e.getErrorCode();

        // Oracle: ORA-00001 (unique constraint violated) - SQLState 23000
        // H2: SQLState 23505 (unique constraint violation)
        // PostgreSQL: SQLState 23505
        // MySQL: error code 1062, SQLState 23000
        return "23000".equals(sqlState) ||
               "23505".equals(sqlState) ||
               errorCode == 1 ||        // Oracle ORA-00001
               errorCode == 1062 ||     // MySQL
               (e.getMessage() != null && e.getMessage().toLowerCase().contains("unique"));
    }

    /**
     * Returns the table name used for storing locks.
     *
     * @return the table name
     */
    public String getTableName() {
        return tableName;
    }
}
