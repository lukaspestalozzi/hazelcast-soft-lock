package com.github.reservation.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * Micrometer-backed implementation of {@link ReservationMetrics}.
 *
 * <p>This class is only loaded when Micrometer is on the classpath,
 * guarded by the check in {@link ReservationMetrics#create(Object, String)}.</p>
 */
final class MicrometerReservationMetrics implements ReservationMetrics {

    private final MeterRegistry registry;
    private final String backend;

    MicrometerReservationMetrics(Object meterRegistry, String backend) {
        this.registry = (MeterRegistry) meterRegistry;
        this.backend = backend;
    }

    @Override
    public void recordAcquisition(String domain, Duration elapsed, String result) {
        Timer.builder("reservation.acquire")
            .description("Time to acquire reservation")
            .tag("domain", domain)
            .tag("backend", backend)
            .tag("result", result)
            .register(registry)
            .record(elapsed);
    }

    @Override
    public void recordAcquisitionAttempt(String domain, boolean success) {
        Counter.builder("reservation.acquire.attempts")
            .description("Number of acquisition attempts")
            .tag("domain", domain)
            .tag("backend", backend)
            .tag("result", success ? "success" : "failure")
            .register(registry)
            .increment();
    }

    @Override
    public void recordHeldTime(String domain, Duration elapsed) {
        Timer.builder("reservation.held.time")
            .description("Time reservation was held")
            .tag("domain", domain)
            .tag("backend", backend)
            .register(registry)
            .record(elapsed);
    }

    @Override
    public void recordExpiration(String domain) {
        Counter.builder("reservation.expired")
            .description("Number of reservations that expired before unlock")
            .tag("domain", domain)
            .tag("backend", backend)
            .register(registry)
            .increment();
    }
}
