package com.github.reservation;

import com.github.reservation.hazelcast.HazelcastReservationManagerBuilder;
import com.github.reservation.oracle.OracleReservationManagerBuilder;
import com.hazelcast.core.HazelcastInstance;

import javax.sql.DataSource;
import java.io.Closeable;
import java.time.Duration;

/**
 * Factory and manager for {@link Reservation} instances within a single domain.
 *
 * <p>A ReservationManager is bound to a specific backend (Hazelcast or Oracle), a single
 * domain, and configuration. Each manager handles reservations for one domain only.
 * For multiple domains, create multiple managers.</p>
 *
 * <p>Example usage with Hazelcast:</p>
 * <pre>{@code
 * HazelcastInstance hz = HazelcastClient.newHazelcastClient();
 * ReservationManager ordersManager = ReservationManager.hazelcast(hz)
 *     .domain("orders")
 *     .leaseTime(Duration.ofMinutes(1))
 *     .build();
 *
 * Reservation reservation = ordersManager.getReservation("12345");
 * reservation.lock();
 * try {
 *     // critical section
 * } finally {
 *     reservation.unlock();
 * }
 * }</pre>
 *
 * <p>Example usage with Oracle:</p>
 * <pre>{@code
 * DataSource dataSource = ...;
 * ReservationManager manager = ReservationManager.oracle(dataSource)
 *     .domain("orders")
 *     .leaseTime(Duration.ofMinutes(1))
 *     .build();
 * }</pre>
 */
public interface ReservationManager extends Closeable {

    /**
     * Creates a new builder for a Hazelcast-backed ReservationManager.
     *
     * @param hazelcastInstance the Hazelcast client instance to use
     * @return a new builder instance
     * @throws NullPointerException if hazelcastInstance is null
     */
    static HazelcastReservationManagerBuilder hazelcast(HazelcastInstance hazelcastInstance) {
        return new HazelcastReservationManagerBuilder(hazelcastInstance);
    }

    /**
     * Creates a new builder for an Oracle-backed ReservationManager.
     *
     * @param dataSource the DataSource to use for database connections
     * @return a new builder instance
     * @throws NullPointerException if dataSource is null
     */
    static OracleReservationManagerBuilder oracle(DataSource dataSource) {
        return new OracleReservationManagerBuilder(dataSource);
    }

    /**
     * Obtains a reservation for the given identifier within this manager's domain.
     *
     * <p>This method always returns a new Reservation instance, but the underlying
     * distributed lock is shared across all instances with the same identifier.</p>
     *
     * @param identifier the identifier within the domain (e.g., order ID, user ID)
     * @return a Reservation instance for the given identifier
     * @throws InvalidReservationKeyException if identifier is null or empty
     */
    Reservation getReservation(String identifier);

    /**
     * Returns the domain managed by this ReservationManager.
     *
     * @return the domain string, never null
     */
    String getDomain();

    /**
     * Returns the configured lease time for reservations created by this manager.
     *
     * @return the lease time duration
     */
    Duration getLeaseTime();

    /**
     * Closes this manager and releases associated resources.
     *
     * <p>Note: This does NOT close the underlying Hazelcast instance or DataSource,
     * nor does it release any currently held reservations.</p>
     */
    @Override
    void close();
}
