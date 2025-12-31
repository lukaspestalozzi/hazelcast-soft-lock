package com.github.reservation.oracle;

import com.github.reservation.AbstractReservationManagerBuilder;
import com.github.reservation.ReservationManager;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Builder for creating Oracle/JDBC-backed {@link ReservationManager} instances.
 *
 * <p>Each ReservationManager manages a single domain.</p>
 */
public final class OracleReservationManagerBuilder
        extends AbstractReservationManagerBuilder<OracleReservationManagerBuilder> {

    private final DataSource dataSource;
    private String tableName = "RESERVATION_LOCKS";
    private LockingStrategy lockingStrategy = null;

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

    @Override
    public ReservationManager build() {
        validate();
        LockingStrategy strategy = lockingStrategy != null
            ? lockingStrategy
            : new TableBasedLockingStrategy(dataSource, tableName);

        return new OracleReservationManager(
            dataSource,
            strategy,
            domain,
            leaseTime,
            tableName,
            meterRegistry
        );
    }
}
