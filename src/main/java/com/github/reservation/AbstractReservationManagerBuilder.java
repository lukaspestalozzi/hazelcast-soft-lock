package com.github.reservation;

import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.Objects;

/**
 * Base builder with common configuration for all ReservationManager implementations.
 *
 * @param <T> the concrete builder type for fluent API
 */
public abstract class AbstractReservationManagerBuilder<T extends AbstractReservationManagerBuilder<T>> {

    protected Duration leaseTime = Duration.ofMinutes(1);
    protected String delimiter = "::";
    protected MeterRegistry meterRegistry = null;

    /**
     * Sets the lease time for reservations. Default: 1 minute.
     *
     * @param leaseTime the lease time duration (must be positive)
     * @return this builder
     * @throws IllegalArgumentException if leaseTime is null, zero, or negative
     */
    @SuppressWarnings("unchecked")
    public T leaseTime(Duration leaseTime) {
        Objects.requireNonNull(leaseTime, "leaseTime must not be null");
        if (leaseTime.isZero() || leaseTime.isNegative()) {
            throw new IllegalArgumentException("leaseTime must be positive");
        }
        this.leaseTime = leaseTime;
        return (T) this;
    }

    /**
     * Sets the delimiter for composite keys. Default: "::"
     *
     * @param delimiter the delimiter string (must not be null or empty)
     * @return this builder
     */
    @SuppressWarnings("unchecked")
    public T delimiter(String delimiter) {
        Objects.requireNonNull(delimiter, "delimiter must not be null");
        if (delimiter.isEmpty()) {
            throw new IllegalArgumentException("delimiter must not be empty");
        }
        this.delimiter = delimiter;
        return (T) this;
    }

    /**
     * Sets the Micrometer registry for metrics. Default: none (metrics disabled)
     *
     * @param meterRegistry the meter registry, or null to disable metrics
     * @return this builder
     */
    @SuppressWarnings("unchecked")
    public T meterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        return (T) this;
    }

    /**
     * Builds the ReservationManager with the configured settings.
     *
     * @return a new ReservationManager instance
     */
    public abstract ReservationManager build();
}
