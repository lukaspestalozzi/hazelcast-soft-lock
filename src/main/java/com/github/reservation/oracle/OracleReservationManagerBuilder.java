package com.github.reservation.oracle;

import com.github.reservation.AbstractReservationManagerBuilder;
import com.github.reservation.ReservationManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Objects;

/**
 * Builder for creating Oracle/JDBC-backed {@link ReservationManager} instances.
 *
 * <p>Each ReservationManager manages a single domain.</p>
 */
public final class OracleReservationManagerBuilder
        extends AbstractReservationManagerBuilder<OracleReservationManagerBuilder> {

    /** Default initial polling interval (50ms) */
    public static final Duration DEFAULT_INITIAL_POLL_INTERVAL = Duration.ofMillis(50);

    /** Default maximum polling interval (1 second) */
    public static final Duration DEFAULT_MAX_POLL_INTERVAL = Duration.ofSeconds(1);

    private final DataSource dataSource;
    private String tableName = "RESERVATION_LOCKS";
    private LockingStrategy lockingStrategy = null;
    private Duration initialPollInterval = DEFAULT_INITIAL_POLL_INTERVAL;
    private Duration maxPollInterval = DEFAULT_MAX_POLL_INTERVAL;
    private boolean validateSchema = false;

    public OracleReservationManagerBuilder(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /**
     * Sets the table name for storing reservations. Default: "RESERVATION_LOCKS"
     *
     * <p>Note: The table must be created by the user. See documentation for required schema.</p>
     *
     * @param tableName the table name (must not be null or empty)
     * @return this builder
     */
    public OracleReservationManagerBuilder tableName(String tableName) {
        Objects.requireNonNull(tableName, "tableName must not be null");
        if (tableName.isEmpty()) {
            throw new IllegalArgumentException("tableName must not be empty");
        }
        this.tableName = tableName;
        return this;
    }

    /**
     * Sets a custom locking strategy. Default: {@link TableBasedLockingStrategy}
     *
     * <p>This allows experimentation with different locking mechanisms.</p>
     *
     * @param lockingStrategy the custom locking strategy
     * @return this builder
     */
    public OracleReservationManagerBuilder lockingStrategy(LockingStrategy lockingStrategy) {
        this.lockingStrategy = Objects.requireNonNull(lockingStrategy, "lockingStrategy must not be null");
        return this;
    }

    /**
     * Sets the initial polling interval for lock acquisition retry. Default: 50ms
     *
     * <p>When waiting for a lock, the implementation starts with this interval
     * and doubles it on each retry, up to the maximum poll interval.</p>
     *
     * @param initialPollInterval the initial poll interval (must be positive)
     * @return this builder
     */
    public OracleReservationManagerBuilder initialPollInterval(Duration initialPollInterval) {
        Objects.requireNonNull(initialPollInterval, "initialPollInterval must not be null");
        if (initialPollInterval.isNegative() || initialPollInterval.isZero()) {
            throw new IllegalArgumentException("initialPollInterval must be positive");
        }
        this.initialPollInterval = initialPollInterval;
        return this;
    }

    /**
     * Sets the maximum polling interval for lock acquisition retry. Default: 1 second
     *
     * <p>The polling interval doubles on each retry but will not exceed this value.</p>
     *
     * @param maxPollInterval the maximum poll interval (must be positive)
     * @return this builder
     */
    public OracleReservationManagerBuilder maxPollInterval(Duration maxPollInterval) {
        Objects.requireNonNull(maxPollInterval, "maxPollInterval must not be null");
        if (maxPollInterval.isNegative() || maxPollInterval.isZero()) {
            throw new IllegalArgumentException("maxPollInterval must be positive");
        }
        this.maxPollInterval = maxPollInterval;
        return this;
    }

    /**
     * Enables schema validation on startup. Default: disabled
     *
     * <p>When enabled, the builder will verify that the lock table exists and has
     * the required columns (reservation_key, holder, acquired_at, expires_at) before
     * creating the manager. If validation fails, an exception is thrown.</p>
     *
     * @return this builder
     */
    public OracleReservationManagerBuilder validateSchema() {
        this.validateSchema = true;
        return this;
    }

    /**
     * Enables or disables schema validation on startup. Default: disabled
     *
     * @param validate true to enable validation, false to disable
     * @return this builder
     */
    public OracleReservationManagerBuilder validateSchema(boolean validate) {
        this.validateSchema = validate;
        return this;
    }

    @Override
    public ReservationManager build() {
        validate();

        // Create or use provided strategy
        LockingStrategy strategy;
        if (lockingStrategy != null) {
            strategy = lockingStrategy;
        } else {
            TableBasedLockingStrategy tableStrategy = new TableBasedLockingStrategy(dataSource, tableName);

            // Validate schema if requested
            if (validateSchema) {
                try {
                    tableStrategy.validateSchema();
                } catch (LockingException e) {
                    throw new IllegalStateException("Schema validation failed: " + e.getMessage(), e);
                }
            }

            strategy = tableStrategy;
        }

        return new OracleReservationManager(
            dataSource,
            strategy,
            domain,
            leaseTime,
            tableName,
            initialPollInterval,
            maxPollInterval,
            meterRegistry
        );
    }
}
