package com.github.reservation.internal;

import java.time.Duration;

/**
 * No-op implementation used when no MeterRegistry was configured.
 * All methods are empty.
 */
final class NoOpReservationMetrics implements ReservationMetrics {

    static final NoOpReservationMetrics INSTANCE = new NoOpReservationMetrics();

    @Override
    public void recordAcquisition(Duration elapsed, String result) {
    }

    @Override
    public void recordAcquisitionAttempt(boolean success) {
    }

    @Override
    public void recordHeldTime(Duration elapsed) {
    }

    @Override
    public void recordExpiration() {
    }
}
