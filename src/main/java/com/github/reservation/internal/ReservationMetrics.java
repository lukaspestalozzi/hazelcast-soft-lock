package com.github.reservation.internal;

import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;

/**
 * Metrics interface for reservation operations. Micrometer is an optional
 * dependency: this interface is only linked against it in the static factory,
 * and the Micrometer-backed implementation is only instantiated when a
 * registry is provided.
 *
 * <p>Instances are scoped to one manager, so backend and domain are fixed
 * at creation time.</p>
 *
 * <p>The recording methods of instances returned by {@link #create} never
 * throw: they run after lock state has already changed, and a throwing
 * registry must not make a successful acquisition look failed (which would
 * leak the lock until lease expiry) or corrupt hold bookkeeping.
 * {@link #create} itself registers meters eagerly and MAY throw on a broken
 * registry — deliberately: it runs at manager build time, before any lock
 * state exists, where a configuration error should fail fast rather than
 * silently degrade metrics to a no-op.</p>
 */
public interface ReservationMetrics {

    void recordAcquisition(Duration elapsed, String result);

    void recordHeldTime(Duration elapsed);

    void recordExpiration();

    /**
     * Creates a ReservationMetrics instance for one manager.
     *
     * <p>May throw if the registry rejects meter registration (see class javadoc):
     * this runs at manager build time, where failing fast on a misconfigured
     * registry is preferable to silently dropping metrics.</p>
     *
     * @param meterRegistry the registry to record to, or null for no-op
     * @param backend backend identifier tag (e.g. "hazelcast")
     * @param domain the manager's domain tag
     * @return a metrics instance whose recording methods never throw, never null
     */
    static ReservationMetrics create(MeterRegistry meterRegistry, String backend, String domain) {
        if (meterRegistry == null) {
            return NoOpReservationMetrics.INSTANCE;
        }
        return new GuardedReservationMetrics(
            new MicrometerReservationMetrics(meterRegistry, backend, domain));
    }
}
