package com.github.reservation.hazelcast;

import com.github.reservation.Reservation;
import com.github.reservation.ReservationManager;
import com.github.reservation.internal.ReservationKeyBuilder;
import com.github.reservation.internal.ReservationMetrics;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;

/**
 * Hazelcast-backed implementation of {@link ReservationManager}.
 */
public final class HazelcastReservationManager implements ReservationManager {

    private final HazelcastInstance hazelcastInstance;
    private final IMap<String, String> lockMap;
    private final Duration leaseTime;
    private final String delimiter;
    private final String mapName;
    private final ReservationKeyBuilder keyBuilder;
    private final ReservationMetrics metrics;

    HazelcastReservationManager(
            HazelcastInstance hazelcastInstance,
            Duration leaseTime,
            String delimiter,
            String mapName,
            MeterRegistry meterRegistry) {
        this.hazelcastInstance = hazelcastInstance;
        this.lockMap = hazelcastInstance.getMap(mapName);
        this.leaseTime = leaseTime;
        this.delimiter = delimiter;
        this.mapName = mapName;
        this.keyBuilder = new ReservationKeyBuilder(delimiter);
        this.metrics = new ReservationMetrics(meterRegistry, "hazelcast");
    }

    @Override
    public Reservation getReservation(String domain, String identifier) {
        String reservationKey = keyBuilder.buildKey(domain, identifier);
        return new HazelcastReservation(
            lockMap,
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
