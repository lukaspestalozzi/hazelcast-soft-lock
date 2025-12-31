package com.github.reservation;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * A distributed reservation (soft-lock) that automatically expires after a configured lease time.
 *
 * <p>This reservation is identified by a domain and identifier combination, allowing
 * logical grouping of locks (e.g., domain="orders", identifier="12345").</p>
 *
 * <p><b>Important:</b> The {@link #newCondition()} method is not supported for
 * distributed locks and will throw {@link UnsupportedOperationException}.</p>
 *
 * <p><b>Warning:</b> If the lease time expires while the reservation is held, calling
 * {@link #unlock()} will throw {@link ReservationExpiredException}. This indicates that
 * the critical section guarantee may have been violated.</p>
 */
public interface Reservation extends Lock {

    /**
     * Returns the domain of this reservation.
     *
     * @return the domain string, never null
     */
    String getDomain();

    /**
     * Returns the identifier of this reservation within its domain.
     *
     * @return the identifier string, never null
     */
    String getIdentifier();

    /**
     * Returns the composite key used for this reservation.
     * Format: "{domain}{delimiter}{identifier}"
     *
     * @return the composite key string, never null
     */
    String getReservationKey();

    /**
     * Returns the remaining lease time for this reservation.
     *
     * @return remaining lease time, or {@link Duration#ZERO} if not held
     *         or lease has expired
     */
    Duration getRemainingLeaseTime();

    /**
     * Checks if this reservation is currently held by any thread/process.
     *
     * @return true if the reservation is held, false otherwise
     */
    boolean isLocked();

    /**
     * Checks if this reservation is held by the current thread.
     *
     * @return true if current thread holds the reservation, false otherwise
     */
    boolean isHeldByCurrentThread();

    /**
     * Forces the release of this reservation regardless of ownership.
     *
     * <p><b>Warning:</b> This is an administrative operation that should only
     * be used for recovery scenarios. It will release the reservation even if held
     * by another thread or process.</p>
     */
    void forceUnlock();

    /**
     * Acquires the reservation, blocking until available.
     * The reservation will automatically be released after the configured lease time.
     *
     * @throws ReservationAcquisitionException if the reservation cannot be acquired
     */
    @Override
    void lock();

    /**
     * Acquires the reservation unless the current thread is interrupted.
     *
     * @throws InterruptedException if the current thread is interrupted
     * @throws ReservationAcquisitionException if the reservation cannot be acquired
     */
    @Override
    void lockInterruptibly() throws InterruptedException;

    /**
     * Acquires the reservation only if it is free at the time of invocation.
     *
     * @return true if the reservation was acquired, false otherwise
     */
    @Override
    boolean tryLock();

    /**
     * Acquires the reservation if it becomes available within the given waiting time.
     *
     * @param time the maximum time to wait for the reservation
     * @param unit the time unit of the time argument
     * @return true if the reservation was acquired, false if the waiting time elapsed
     * @throws InterruptedException if the current thread is interrupted
     */
    @Override
    boolean tryLock(long time, TimeUnit unit) throws InterruptedException;

    /**
     * Releases the reservation.
     *
     * @throws ReservationExpiredException if the lease time has expired before unlock
     * @throws IllegalMonitorStateException if the current thread does not hold the reservation
     */
    @Override
    void unlock();

    /**
     * Not supported for distributed reservations.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    default Condition newCondition() {
        throw new UnsupportedOperationException(
            "Conditions are not supported for distributed reservations. " +
            "Consider using a distributed coordination service for complex synchronization.");
    }
}
