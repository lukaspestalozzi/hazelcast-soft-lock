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
 */
public interface ReservationMetrics {

    void recordAcquisition(Duration elapsed, String result);

    void recordAcquisitionAttempt(boolean success);

    void recordHeldTime(Duration elapsed);

    void recordExpiration();

    /**
     * Creates a ReservationMetrics instance for one manager.
     *
     * @param meterRegistry the registry to record to, or null for no-op
     * @param backend backend identifier tag (e.g. "hazelcast")
     * @param domain the manager's domain tag
     * @return a metrics instance, never null
     */
    static ReservationMetrics create(MeterRegistry meterRegistry, String backend, String domain) {
        if (meterRegistry == null) {
            return NoOpReservationMetrics.INSTANCE;
        }
        return new MicrometerReservationMetrics(meterRegistry, backend, domain);
    }
}
