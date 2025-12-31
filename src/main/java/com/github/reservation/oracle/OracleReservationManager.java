package com.github.reservation.oracle;

import com.github.reservation.Reservation;
import com.github.reservation.ReservationManager;
import com.github.reservation.internal.ReservationKeyBuilder;
import com.github.reservation.internal.ReservationMetrics;
import io.micrometer.core.instrument.MeterRegistry;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * Oracle/JDBC-backed implementation of {@link ReservationManager}.
 */
public final class OracleReservationManager implements ReservationManager {

    private final DataSource dataSource;
    private final LockingStrategy lockingStrategy;
    private final Duration leaseTime;
    private final String delimiter;
    private final String tableName;
    private final ReservationKeyBuilder keyBuilder;
    private final ReservationMetrics metrics;

    OracleReservationManager(
            DataSource dataSource,
            LockingStrategy lockingStrategy,
            Duration leaseTime,
            String delimiter,
            String tableName,
            MeterRegistry meterRegistry) {
        this.dataSource = dataSource;
        this.lockingStrategy = lockingStrategy;
        this.leaseTime = leaseTime;
        this.delimiter = delimiter;
        this.tableName = tableName;
        this.keyBuilder = new ReservationKeyBuilder(delimiter);
        this.metrics = new ReservationMetrics(meterRegistry, "oracle");
    }

    @Override
    public Reservation getReservation(String domain, String identifier) {
        String reservationKey = keyBuilder.buildKey(domain, identifier);
        return new OracleReservation(
            lockingStrategy,
            domain,
            identifier,
            reservationKey,
            leaseTime,
            metrics
        );
    }

    @Override
    public Duration getLeaseTime() {
        return leaseTime;
    }

    @Override
    public String getDelimiter() {
        return delimiter;
    }

    /**
     * Returns the table name used for storing reservations.
     *
     * @return the table name
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Returns the locking strategy used by this manager.
     *
     * @return the locking strategy
     */
    public LockingStrategy getLockingStrategy() {
        return lockingStrategy;
    }

    @Override
    public void close() {
        // Do not close the DataSource - it's managed externally
    }
}
