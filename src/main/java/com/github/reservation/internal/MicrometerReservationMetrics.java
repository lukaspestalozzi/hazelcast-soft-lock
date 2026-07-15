package com.github.reservation.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Micrometer-backed implementation of {@link ReservationMetrics}.
 *
 * <p>Meters are created once and cached: backend and domain tags are fixed per manager
 * and the result tag space is tiny, so rebuilding meter ids on the lock/unlock hot path
 * would be pointless allocation and registry lookups.</p>
 */
final class MicrometerReservationMetrics implements ReservationMetrics {

    private final MeterRegistry registry;
    private final String backend;
    private final String domain;

    private final Map<String, Timer> acquireTimersByResult = new ConcurrentHashMap<>();
    private final Timer heldTimer;
    private final Counter expiredCounter;

    MicrometerReservationMetrics(MeterRegistry registry, String backend, String domain) {
        this.registry = registry;
        this.backend = backend;
        this.domain = domain;
        this.heldTimer = Timer.builder("reservation.held.time")
            .description("Time reservation was held")
            .tag("domain", domain)
            .tag("backend", backend)
            .register(registry);
        this.expiredCounter = Counter.builder("reservation.expired")
            .description("Number of reservations whose ownership was lost before unlock")
            .tag("domain", domain)
            .tag("backend", backend)
            .register(registry);
    }

    @Override
    public void recordAcquisition(Duration elapsed, String result) {
        acquireTimersByResult.computeIfAbsent(result, r ->
            Timer.builder("reservation.acquire")
                .description("Time to acquire reservation")
                .tag("domain", domain)
                .tag("backend", backend)
                .tag("result", r)
                .register(registry))
            .record(elapsed);
    }

    @Override
    public void recordHeldTime(Duration elapsed) {
        heldTimer.record(elapsed);
    }

    @Override
    public void recordExpiration() {
        expiredCounter.increment();
    }
}
