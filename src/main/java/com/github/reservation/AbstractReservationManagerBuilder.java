package com.github.reservation;

import java.time.Duration;
import java.util.Objects;

/**
 * Base builder with common configuration for all ReservationManager implementations.
 *
 * @param <T> the concrete builder type for fluent API
 */
public abstract class AbstractReservationManagerBuilder<T extends AbstractReservationManagerBuilder<T>> {

    protected String domain;
    protected Duration leaseTime = Duration.ofMinutes(1);
    /** Stored as Object to avoid a hard dependency on Micrometer at class-load time. */
    protected Object meterRegistry = null;

    /**
     * Sets the domain for this ReservationManager. This is required.
     *
     * <p>Each ReservationManager manages reservations for a single domain.
     * For Hazelcast, each domain uses a separate IMap for isolation.</p>
     *
     * @param domain the domain name (e.g., "orders", "users", "inventory")
     * @return this builder
     * @throws NullPointerException if domain is null
     * @throws IllegalArgumentException if domain is empty
     */
    @SuppressWarnings("unchecked")
    public T domain(String domain) {
        Objects.requireNonNull(domain, "domain must not be null");
        if (domain.isEmpty()) {
            throw new IllegalArgumentException("domain must not be empty");
        }
        this.domain = domain;
        return (T) this;
    }

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
     * Sets the Micrometer registry for metrics. Default: none (metrics disabled).
     *
     * <p>Pass a {@code io.micrometer.core.instrument.MeterRegistry} instance.
     * If Micrometer is not on the classpath, this setting is silently ignored.</p>
     *
     * @param meterRegistry the meter registry, or null to disable metrics
     * @return this builder
     */
    @SuppressWarnings("unchecked")
    public T meterRegistry(Object meterRegistry) {
        this.meterRegistry = meterRegistry;
        return (T) this;
    }

    /**
     * Validates that all required fields are set.
     *
     * @throws IllegalStateException if required fields are missing
     */
    protected void validate() {
        if (domain == null) {
            throw new IllegalStateException("domain must be set before building");
        }
    }

    /**
     * Builds the ReservationManager with the configured settings.
     *
     * @return a new ReservationManager instance
     * @throws IllegalStateException if required configuration is missing
     */
    public abstract ReservationManager build();
}
