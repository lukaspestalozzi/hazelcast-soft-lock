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
 * <p>This strategy uses an UPDATE-then-INSERT pattern inspired by Spring Integration's
 * JdbcLockRegistry: first attempt to UPDATE an existing lock (if owned by same holder
 * or expired), then INSERT if no matching row exists.</p>
 *
 * <p>This approach is more efficient than DELETE-then-INSERT because:</p>
 * <ul>
 *   <li>Reentrant locks (same holder) require only 1 UPDATE</li>
 *   <li>Expired lock acquisition requires only 1 UPDATE</li>
 *   <li>New locks require UPDATE (0 rows) + INSERT</li>
 * </ul>
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
    private final String updateOrAcquireSql;
    private final String deleteSql;
    private final String deleteForceSql;
    private final String selectSql;

    public TableBasedLockingStrategy(DataSource dataSource, String tableName) {
        this.dataSource = dataSource;
        this.tableName = tableName;

        // Initialize SQL with table name
        // Use standard SQL that works with both Oracle and H2
        this.insertSql = String.format(
            "INSERT INTO %s (reservation_key, holder, acquired_at, expires_at) VALUES (?, ?, ?, ?)",
            tableName);

        // UPDATE-then-INSERT pattern: update if owned by same holder OR expired
        this.updateOrAcquireSql = String.format(
            "UPDATE %s SET holder = ?, acquired_at = ?, expires_at = ? " +
            "WHERE reservation_key = ? AND (holder = ? OR expires_at < ?)",
            tableName);

        this.deleteSql = String.format(
            "DELETE FROM %s WHERE reservation_key = ? AND holder = ? AND expires_at > ?",
            tableName);
        this.deleteForceSql = String.format(
            "DELETE FROM %s WHERE reservation_key = ?",
            tableName);
        this.selectSql = String.format(
            "SELECT holder, acquired_at, expires_at FROM %s WHERE reservation_key = ? AND expires_at > ?",
            tableName);
    }

    @Override
    public boolean tryAcquire(String reservationKey, String holder, Duration leaseTime) throws LockingException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Timestamp now = Timestamp.from(Instant.now());
                Timestamp expiresAt = Timestamp.from(Instant.now().plus(leaseTime));

                // Step 1: Try UPDATE - succeeds if we own the lock OR lock is expired
                int updated;
                try (PreparedStatement ps = conn.prepareStatement(updateOrAcquireSql)) {
                    ps.setString(1, holder);      // new holder
                    ps.setTimestamp(2, now);      // new acquired_at
                    ps.setTimestamp(3, expiresAt); // new expires_at
                    ps.setString(4, reservationKey);
                    ps.setString(5, holder);      // match current holder (reentrant)
                    ps.setTimestamp(6, now);      // OR expired (expires_at < now)
                    updated = ps.executeUpdate();
                }

                if (updated > 0) {
                    conn.commit();
                    log.debug("Acquired lock (via update): {} by {}", reservationKey, holder);
                    return true;
                }

                // Step 2: No existing row matched - try INSERT for new lock
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, reservationKey);
                    ps.setString(2, holder);
                    ps.setTimestamp(3, now);
                    ps.setTimestamp(4, expiresAt);
                    ps.executeUpdate();
                }

                conn.commit();
                log.debug("Acquired lock (via insert): {} by {}", reservationKey, holder);
                return true;

            } catch (SQLException e) {
                conn.rollback();

                // Check if it's a unique constraint violation (lock held by another)
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

    /**
     * Validates that the required table exists with the expected schema.
     *
     * <p>Checks for:</p>
     * <ul>
     *   <li>Table existence</li>
     *   <li>Required columns: reservation_key, holder, acquired_at, expires_at</li>
     * </ul>
     *
     * @throws LockingException if validation fails
     */
    public void validateSchema() throws LockingException {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();

            // Check table exists (try uppercase and lowercase for compatibility)
            boolean tableExists = false;
            String actualTableName = null;

            try (ResultSet tables = metaData.getTables(null, null, tableName.toUpperCase(), new String[]{"TABLE"})) {
                if (tables.next()) {
                    tableExists = true;
                    actualTableName = tables.getString("TABLE_NAME");
                }
            }

            if (!tableExists) {
                try (ResultSet tables = metaData.getTables(null, null, tableName.toLowerCase(), new String[]{"TABLE"})) {
                    if (tables.next()) {
                        tableExists = true;
                        actualTableName = tables.getString("TABLE_NAME");
                    }
                }
            }

            if (!tableExists) {
                throw new LockingException("Table '" + tableName + "' does not exist. " +
                    "Please create it using the following schema:\n" +
                    "CREATE TABLE " + tableName + " (\n" +
                    "    reservation_key  VARCHAR(512)  NOT NULL,\n" +
                    "    holder           VARCHAR(256)  NOT NULL,\n" +
                    "    acquired_at      TIMESTAMP     NOT NULL,\n" +
                    "    expires_at       TIMESTAMP     NOT NULL,\n" +
                    "    PRIMARY KEY (reservation_key)\n" +
                    ");");
            }

            // Check required columns exist
            String[] requiredColumns = {"reservation_key", "holder", "acquired_at", "expires_at"};
            java.util.Set<String> foundColumns = new java.util.HashSet<>();

            try (ResultSet columns = metaData.getColumns(null, null, actualTableName, null)) {
                while (columns.next()) {
                    foundColumns.add(columns.getString("COLUMN_NAME").toLowerCase());
                }
            }

            java.util.List<String> missingColumns = new java.util.ArrayList<>();
            for (String required : requiredColumns) {
                if (!foundColumns.contains(required.toLowerCase())) {
                    missingColumns.add(required);
                }
            }

            if (!missingColumns.isEmpty()) {
                throw new LockingException("Table '" + tableName + "' is missing required columns: " +
                    String.join(", ", missingColumns));
            }

            log.debug("Schema validation passed for table: {}", tableName);

        } catch (SQLException e) {
            throw new LockingException("Failed to validate schema for table: " + tableName, e);
        }
    }
}
