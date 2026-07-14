package com.github.reservation.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * Micrometer-backed implementation of {@link ReservationMetrics}.
 */
final class MicrometerReservationMetrics implements ReservationMetrics {

    private final MeterRegistry registry;
    private final String backend;
    private final String domain;

    MicrometerReservationMetrics(MeterRegistry registry, String backend, String domain) {
        this.registry = registry;
        this.backend = backend;
        this.domain = domain;
    }

    @Override
    public void recordAcquisition(Duration elapsed, String result) {
        Timer.builder("reservation.acquire")
            .description("Time to acquire reservation")
            .tag("domain", domain)
            .tag("backend", backend)
            .tag("result", result)
            .register(registry)
            .record(elapsed);
    }

    @Override
    public void recordAcquisitionAttempt(boolean success) {
        Counter.builder("reservation.acquire.attempts")
            .description("Number of acquisition attempts")
            .tag("domain", domain)
            .tag("backend", backend)
            .tag("result", success ? "success" : "failure")
            .register(registry)
            .increment();
    }

    @Override
    public void recordHeldTime(Duration elapsed) {
        Timer.builder("reservation.held.time")
            .description("Time reservation was held")
            .tag("domain", domain)
            .tag("backend", backend)
            .register(registry)
            .record(elapsed);
    }

    @Override
    public void recordExpiration() {
        Counter.builder("reservation.expired")
            .description("Number of reservations that expired before unlock")
            .tag("domain", domain)
            .tag("backend", backend)
            .register(registry)
            .increment();
    }
}
