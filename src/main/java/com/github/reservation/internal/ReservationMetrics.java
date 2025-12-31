package com.github.reservation.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * Micrometer metrics for reservation operations.
 */
public final class ReservationMetrics {

    private final MeterRegistry registry;
    private final String backend;

    public ReservationMetrics(MeterRegistry registry, String backend) {
        this.registry = registry;
        this.backend = backend;
    }

    public void recordAcquisition(String domain, Duration elapsed, String result) {
        if (registry == null) return;

        Timer.builder("reservation.acquire")
            .description("Time to acquire reservation")
            .tag("domain", domain)
            .tag("backend", backend)
            .tag("result", result)
            .register(registry)
            .record(elapsed);
    }

    public void recordAcquisitionAttempt(String domain, boolean success) {
        if (registry == null) return;

        Counter.builder("reservation.acquire.attempts")
            .description("Number of acquisition attempts")
            .tag("domain", domain)
            .tag("backend", backend)
            .tag("result", success ? "success" : "failure")
            .register(registry)
            .increment();
    }

    public void recordHeldTime(String domain, Duration elapsed) {
        if (registry == null) return;

        Timer.builder("reservation.held.time")
            .description("Time reservation was held")
            .tag("domain", domain)
            .tag("backend", backend)
            .register(registry)
            .record(elapsed);
    }

    public void recordExpiration(String domain) {
        if (registry == null) return;

        Counter.builder("reservation.expired")
            .description("Number of reservations that expired before unlock")
            .tag("domain", domain)
            .tag("backend", backend)
            .register(registry)
            .increment();
    }

    public boolean isEnabled() {
        return registry != null;
    }
}
