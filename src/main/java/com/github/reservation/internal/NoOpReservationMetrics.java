package com.github.reservation.internal;

import java.time.Duration;

/**
 * No-op implementation used when Micrometer is not on the classpath or no
 * MeterRegistry was configured. All methods are empty.
 */
final class NoOpReservationMetrics implements ReservationMetrics {

    static final NoOpReservationMetrics INSTANCE = new NoOpReservationMetrics();

    @Override
    public void recordAcquisition(String domain, Duration elapsed, String result) {
    }

    @Override
    public void recordAcquisitionAttempt(String domain, boolean success) {
    }

    @Override
    public void recordHeldTime(String domain, Duration elapsed) {
    }

    @Override
    public void recordExpiration(String domain) {
    }
}
