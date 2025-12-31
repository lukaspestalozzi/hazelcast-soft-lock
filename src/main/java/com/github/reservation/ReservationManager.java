package com.github.reservation;

import com.github.reservation.hazelcast.HazelcastReservationManagerBuilder;
import com.github.reservation.oracle.OracleReservationManagerBuilder;
import com.hazelcast.core.HazelcastInstance;

import javax.sql.DataSource;
import java.io.Closeable;
import java.time.Duration;

/**
 * Factory and manager for {@link Reservation} instances.
 *
 * <p>A ReservationManager is bound to a specific backend (Hazelcast or Oracle) and
 * configuration. Multiple managers can coexist, each with their own settings.</p>
 *
 * <p>Example usage with Hazelcast:</p>
 * <pre>{@code
 * HazelcastInstance hz = HazelcastClient.newHazelcastClient();
 * ReservationManager manager = ReservationManager.hazelcast(hz)
 *     .leaseTime(Duration.ofMinutes(1))
 *     .build();
 *
 * Reservation reservation = manager.getReservation("orders", "12345");
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
     * Obtains a reservation for the given domain and identifier.
     *
     * <p>This method always returns a new Reservation instance, but the underlying
     * distributed lock is shared across all instances with the same domain/identifier.</p>
     *
     * @param domain the reservation domain (e.g., "orders", "users", "inventory")
     * @param identifier the identifier within the domain (e.g., order ID, user ID)
     * @return a Reservation instance for the given domain and identifier
     * @throws InvalidReservationKeyException if domain or identifier is null, empty,
     *         or contains the configured delimiter
     */
    Reservation getReservation(String domain, String identifier);

    /**
     * Returns the configured lease time for reservations created by this manager.
     *
     * @return the lease time duration
     */
    Duration getLeaseTime();

    /**
     * Returns the configured delimiter used for composite keys.
     *
     * @return the delimiter string
     */
    String getDelimiter();

    /**
     * Closes this manager and releases associated resources.
     *
     * <p>Note: This does NOT close the underlying Hazelcast instance or DataSource,
     * nor does it release any currently held reservations.</p>
     */
    @Override
    void close();
}
