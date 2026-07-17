package com.github.reservation.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Decorator that makes any {@link ReservationMetrics} implementation exception-safe.
 *
 * <p>Metrics are recorded after lock state has already changed. A throwing registry
 * (closed registry, misbehaving MeterFilter, ...) must never make a successful
 * acquisition look failed — the caller would then never unlock, leaking the lock
 * until lease expiry — nor abort unlock bookkeeping halfway.</p>
 */
final class GuardedReservationMetrics implements ReservationMetrics {

    private static final Logger log = LoggerFactory.getLogger(GuardedReservationMetrics.class);

    private final ReservationMetrics delegate;

    GuardedReservationMetrics(ReservationMetrics delegate) {
        this.delegate = delegate;
    }

    @Override
    public void recordAcquisition(Duration elapsed, String result) {
        try {
            delegate.recordAcquisition(elapsed, result);
        } catch (Exception e) {
            log.debug("Failed to record acquisition metric", e);
        }
    }

    @Override
    public void recordHeldTime(Duration elapsed) {
        try {
            delegate.recordHeldTime(elapsed);
        } catch (Exception e) {
            log.debug("Failed to record held-time metric", e);
        }
    }

    @Override
    public void recordExpiration() {
        try {
            delegate.recordExpiration();
        } catch (Exception e) {
            log.debug("Failed to record expiration metric", e);
        }
    }
}
