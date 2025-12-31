package com.github.reservation.hazelcast;

import com.github.reservation.AbstractReservationManagerBuilder;
import com.github.reservation.ReservationManager;
import com.hazelcast.core.HazelcastInstance;

import java.util.Objects;

/**
 * Builder for creating Hazelcast-backed {@link ReservationManager} instances.
 */
public final class HazelcastReservationManagerBuilder
        extends AbstractReservationManagerBuilder<HazelcastReservationManagerBuilder> {

    private final HazelcastInstance hazelcastInstance;
    private String mapName = "reservations";

    public HazelcastReservationManagerBuilder(HazelcastInstance hazelcastInstance) {
        this.hazelcastInstance = Objects.requireNonNull(hazelcastInstance,
            "hazelcastInstance must not be null");
    }

    /**
     * Sets the Hazelcast IMap name for storing reservations. Default: "reservations"
     *
     * @param mapName the map name (must not be null or empty)
     * @return this builder
     */
    public HazelcastReservationManagerBuilder mapName(String mapName) {
        Objects.requireNonNull(mapName, "mapName must not be null");
        if (mapName.isEmpty()) {
            throw new IllegalArgumentException("mapName must not be empty");
        }
        this.mapName = mapName;
        return this;
    }

    @Override
    public ReservationManager build() {
        return new HazelcastReservationManager(
            hazelcastInstance,
            leaseTime,
            delimiter,
            mapName,
            meterRegistry
        );
    }
}
