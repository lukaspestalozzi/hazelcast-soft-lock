package com.github.reservation.internal;

import java.time.Duration;

/**
 * Metrics interface for reservation operations. Decoupled from Micrometer
 * so the library works without Micrometer on the classpath.
 *
 * <p>Use {@link #create(Object, String)} to obtain an instance. Pass a
 * {@code MeterRegistry} to enable metrics, or {@code null} for no-op.</p>
 */
public interface ReservationMetrics {

    void recordAcquisition(String domain, Duration elapsed, String result);

    void recordAcquisitionAttempt(String domain, boolean success);

    void recordHeldTime(String domain, Duration elapsed);

    void recordExpiration(String domain);

    /**
     * Creates a ReservationMetrics instance. If {@code meterRegistry} is non-null
     * and Micrometer is on the classpath, returns a Micrometer-backed implementation.
     * Otherwise returns a no-op.
     *
     * @param meterRegistry a {@code io.micrometer.core.instrument.MeterRegistry}, or null
     * @param backend backend identifier tag (e.g. "hazelcast", "oracle")
     * @return a metrics instance, never null
     */
    static ReservationMetrics create(Object meterRegistry, String backend) {
        if (meterRegistry == null) {
            return NoOpReservationMetrics.INSTANCE;
        }
        if (!isMicrometerAvailable()) {
            return NoOpReservationMetrics.INSTANCE;
        }
        return new MicrometerReservationMetrics(meterRegistry, backend);
    }

    private static boolean isMicrometerAvailable() {
        try {
            Class.forName("io.micrometer.core.instrument.MeterRegistry");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
