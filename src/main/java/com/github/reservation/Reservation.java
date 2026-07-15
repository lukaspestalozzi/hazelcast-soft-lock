package com.github.reservation;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * A distributed reservation (soft-lock) that automatically expires after a configured lease time.
 *
 * <p>This reservation is identified by an identifier within a domain. The domain is configured
 * on the {@link ReservationManager} that created this reservation.</p>
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
     * Returns the identifier of this reservation within its domain.
     *
     * @return the identifier string, never null
     */
    String getIdentifier();

    /**
     * Returns the remaining lease time for the <b>current thread's</b> hold on this
     * reservation.
     *
     * <p>This is a local estimate derived from the time of the current thread's most
     * recent acquisition and the configured lease time; it does not consult the
     * backend. It can therefore disagree with cluster reality after clock drift or
     * after the reservation was force-released elsewhere.</p>
     *
     * @return remaining lease time, or {@link Duration#ZERO} if the current thread
     *         does not hold the reservation or its lease has expired
     */
    Duration getRemainingLeaseTime();

    /**
     * Checks if this reservation is currently held by <b>any</b> thread or process.
     *
     * <p>To ask whether the calling thread itself holds the reservation, use
     * {@link #isHeldByCurrentThread()}.</p>
     *
     * @return true if the reservation is held, false otherwise
     */
    boolean isLocked();

    /**
     * Checks if the current thread holds this reservation.
     *
     * <p>This reflects the local hold bookkeeping, not a backend query: it returns
     * {@code false} once the lease is (locally judged to be) expired, slightly before
     * the configured lease time fully elapses — implementations apply a safety margin
     * so that clock skew errs toward reporting "not held" rather than falsely
     * reporting a hold that the cluster already released.</p>
     *
     * @return true if the current thread holds an unexpired lease on this reservation
     */
    boolean isHeldByCurrentThread();

    /**
     * Forces the release of this reservation regardless of ownership.
     *
     * <p><b>Warning:</b> This is an administrative operation that should only
     * be used for recovery scenarios. It will release the reservation even if held
     * by another thread or process.</p>
     *
     * <p>Only the calling thread's local hold state can be cleared. If another thread
     * (or process) held the reservation, its local state goes stale: its
     * {@link #getRemainingLeaseTime()} keeps counting down and its eventual
     * {@link #unlock()} throws {@link ReservationExpiredException}.</p>
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
     * <p>If the current thread is interrupted, the interrupt flag is restored
     * and {@code false} is returned (this method does not declare
     * {@link InterruptedException}).</p>
     *
     * @return true if the reservation was acquired, false if it is held by another
     * @throws ReservationAcquisitionException if a backend error prevents acquisition
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
     * @throws ReservationAcquisitionException if a backend error prevents acquisition
     */
    @Override
    boolean tryLock(long time, TimeUnit unit) throws InterruptedException;

    /**
     * Releases the reservation.
     *
     * @throws ReservationExpiredException if ownership was already lost when unlocking —
     *         most commonly because the lease expired, but also after an administrative
     *         {@link #forceUnlock()} by another thread or process
     * @throws ReservationReleaseException if an infrastructure failure prevents the
     *         release; the local hold is kept and the call may be retried
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
