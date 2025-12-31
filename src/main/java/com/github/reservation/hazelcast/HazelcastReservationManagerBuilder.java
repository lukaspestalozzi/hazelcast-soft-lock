package com.github.reservation.hazelcast;

import com.github.reservation.AbstractReservationManagerBuilder;
import com.github.reservation.ReservationManager;
import com.hazelcast.core.HazelcastInstance;

import java.util.Objects;

/**
 * Builder for creating Hazelcast-backed {@link ReservationManager} instances.
 *
 * <p>Each ReservationManager manages a single domain, and uses a dedicated
 * Hazelcast IMap for that domain (named "reservations-{domain}").</p>
 */
public final class HazelcastReservationManagerBuilder
        extends AbstractReservationManagerBuilder<HazelcastReservationManagerBuilder> {

    private final HazelcastInstance hazelcastInstance;
    private String mapPrefix = "reservations";

    public HazelcastReservationManagerBuilder(HazelcastInstance hazelcastInstance) {
        this.hazelcastInstance = Objects.requireNonNull(hazelcastInstance,
            "hazelcastInstance must not be null");
    }

    /**
     * Sets the prefix for the Hazelcast IMap name. Default: "reservations"
     *
     * <p>The actual map name will be "{prefix}-{domain}".</p>
     *
     * @param mapPrefix the map name prefix (must not be null or empty)
     * @return this builder
     */
    public HazelcastReservationManagerBuilder mapPrefix(String mapPrefix) {
        Objects.requireNonNull(mapPrefix, "mapPrefix must not be null");
        if (mapPrefix.isEmpty()) {
            throw new IllegalArgumentException("mapPrefix must not be empty");
        }
        this.mapPrefix = mapPrefix;
        return this;
    }

    @Override
    public ReservationManager build() {
        validate();
        String mapName = mapPrefix + "-" + domain;
        return new HazelcastReservationManager(
            hazelcastInstance,
            domain,
            leaseTime,
            mapName,
            meterRegistry
        );
    }
}
