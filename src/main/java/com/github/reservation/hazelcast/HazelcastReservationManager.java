package com.github.reservation.hazelcast;

import com.github.reservation.InvalidReservationKeyException;
import com.github.reservation.Reservation;
import com.github.reservation.ReservationManager;
import com.github.reservation.internal.ReservationMetrics;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;

/**
 * Hazelcast-backed implementation of {@link ReservationManager}.
 *
 * <p>Each manager handles a single domain and uses a dedicated IMap for that domain.</p>
 */
public final class HazelcastReservationManager implements ReservationManager {

    private final HazelcastInstance hazelcastInstance;
    private final IMap<String, String> lockMap;
    private final String domain;
    private final Duration leaseTime;
    private final String mapName;
    private final ReservationMetrics metrics;

    HazelcastReservationManager(
            HazelcastInstance hazelcastInstance,
            String domain,
            Duration leaseTime,
            String mapName,
            MeterRegistry meterRegistry) {
        this.hazelcastInstance = hazelcastInstance;
        this.domain = domain;
        this.lockMap = hazelcastInstance.getMap(mapName);
        this.leaseTime = leaseTime;
        this.mapName = mapName;
        this.metrics = new ReservationMetrics(meterRegistry, "hazelcast");
    }

    @Override
    public Reservation getReservation(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            throw new InvalidReservationKeyException("identifier must not be null or empty");
        }
        return new HazelcastReservation(
            lockMap,
            domain,
            identifier,
            leaseTime,
            metrics
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
     * Returns the Hazelcast IMap name used for storing reservations.
     *
     * @return the map name
     */
    public String getMapName() {
        return mapName;
    }

    @Override
    public void close() {
        // Do not close the Hazelcast instance - it's managed externally
    }
}
