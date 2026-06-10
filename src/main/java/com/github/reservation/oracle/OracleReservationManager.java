package com.github.reservation.oracle;

import com.github.reservation.InvalidReservationKeyException;
import com.github.reservation.Reservation;
import com.github.reservation.ReservationManager;
import com.github.reservation.internal.HoldTracker;
import com.github.reservation.internal.ReservationKeyBuilder;
import com.github.reservation.internal.ReservationMetrics;
import io.micrometer.core.instrument.MeterRegistry;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;

/**
 * Oracle/JDBC-backed implementation of {@link ReservationManager}.
 *
 * <p>Each manager handles a single domain.</p>
 */
public final class OracleReservationManager implements ReservationManager {

    private static final String KEY_DELIMITER = "::";

    private final DataSource dataSource;
    private final LockingStrategy lockingStrategy;
    private final String domain;
    private final Duration leaseTime;
    private final String tableName;
    private final ReservationMetrics metrics;
    private final ReservationKeyBuilder keyBuilder = new ReservationKeyBuilder(KEY_DELIMITER);
    private final HoldTracker holdTracker = new HoldTracker();
    // Disambiguates holders from different managers in the same JVM/host
    private final String managerId = UUID.randomUUID().toString().substring(0, 8);

    OracleReservationManager(
            DataSource dataSource,
            LockingStrategy lockingStrategy,
            String domain,
            Duration leaseTime,
            String tableName,
            MeterRegistry meterRegistry) {
        this.dataSource = dataSource;
        this.lockingStrategy = lockingStrategy;
        this.domain = domain;
        this.leaseTime = leaseTime;
        this.tableName = tableName;
        this.metrics = new ReservationMetrics(meterRegistry, "oracle");
    }

    @Override
    public Reservation getReservation(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            throw new InvalidReservationKeyException("identifier must not be null or empty");
        }
        // Build key as domain::identifier for database storage; rejects values
        // containing the delimiter to prevent collisions across domains
        String reservationKey = keyBuilder.buildKey(domain, identifier);
        return new OracleReservation(
            lockingStrategy,
            domain,
            identifier,
            reservationKey,
            leaseTime,
            metrics,
            holdTracker,
            managerId
        );
    }

    @Override
    public String getDomain() {
        return domain;
    }

    @Override
    public Duration getLeaseTime() {
        return leaseTime;
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
