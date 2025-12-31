package com.github.reservation.oracle;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Strategy interface for database-based locking mechanisms.
 *
 * <p>This interface allows experimentation with different locking approaches
 * without changing the rest of the implementation.</p>
 *
 * <p>Implementations must be thread-safe.</p>
 */
public interface LockingStrategy {

    /**
     * Attempts to acquire a lock.
     *
     * @param reservationKey the composite key (domain::identifier)
     * @param holder unique identifier for the lock holder (thread@host)
     * @param leaseTime how long the lock should be held before auto-expiry
     * @return true if lock was acquired, false if already held by another
     * @throws LockingException if a database error occurs
     */
    boolean tryAcquire(String reservationKey, String holder, Duration leaseTime) throws LockingException;

    /**
     * Releases a lock.
     *
     * @param reservationKey the composite key
     * @param holder the holder that acquired the lock
     * @return true if lock was released, false if not held or expired
     * @throws LockingException if a database error occurs
     */
    boolean release(String reservationKey, String holder) throws LockingException;

    /**
     * Forcefully releases a lock regardless of owner.
     *
     * @param reservationKey the composite key
     * @throws LockingException if a database error occurs
     */
    void forceRelease(String reservationKey) throws LockingException;

    /**
     * Checks if a lock is currently held (and not expired).
     *
     * @param reservationKey the composite key
     * @return true if lock is held, false otherwise
     * @throws LockingException if a database error occurs
     */
    boolean isLocked(String reservationKey) throws LockingException;

    /**
     * Gets lock information if held.
     *
     * @param reservationKey the composite key
     * @return lock info if held, empty if not
     * @throws LockingException if a database error occurs
     */
    Optional<LockInfo> getLockInfo(String reservationKey) throws LockingException;

    /**
     * Information about a held lock.
     */
    record LockInfo(
        String holder,
        Instant acquiredAt,
        Instant expiresAt
    ) {
        public Duration remainingLeaseTime() {
            Duration remaining = Duration.between(Instant.now(), expiresAt);
            return remaining.isNegative() ? Duration.ZERO : remaining;
        }
    }
}
