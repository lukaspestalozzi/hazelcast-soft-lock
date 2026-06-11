package com.github.reservation.hazelcast;

import com.github.reservation.Reservation;
import com.github.reservation.ReservationAcquisitionException;
import com.github.reservation.ReservationExpiredException;
import com.github.reservation.internal.HoldTracker;
import com.github.reservation.internal.ReservationMetrics;
import com.hazelcast.map.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Hazelcast-backed implementation of {@link Reservation}.
 */
final class HazelcastReservation implements Reservation {

    private static final Logger log = LoggerFactory.getLogger(HazelcastReservation.class);

    // IMap.lock() is not interruptible, so lockInterruptibly() polls with tryLock in
    // slices of this length and checks the interrupt flag between attempts.
    private static final long INTERRUPT_POLL_MILLIS = 100;

    // Resolved once; a DNS lookup per lock operation is needless overhead
    private static final String HOST_NAME = resolveHostName();

    private final IMap<String, String> lockMap;
    private final String domain;
    private final String identifier;
    private final Duration leaseTime;
    private final ReservationMetrics metrics;
    private final HoldTracker holdTracker;

    HazelcastReservation(
            IMap<String, String> lockMap,
            String domain,
            String identifier,
            Duration leaseTime,
            ReservationMetrics metrics,
            HoldTracker holdTracker) {
        this.lockMap = lockMap;
        this.domain = domain;
        this.identifier = identifier;
        this.leaseTime = leaseTime;
        this.metrics = metrics;
        this.holdTracker = holdTracker;
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String getReservationKey() {
        // In single-domain mode, the key is just the identifier
        // (the domain isolation is handled by using separate maps)
        return identifier;
    }

    @Override
    public Duration getRemainingLeaseTime() {
        HoldTracker.Hold hold = holdTracker.get(identifier);
        if (hold == null) {
            return Duration.ZERO;
        }
        Duration elapsed = Duration.between(hold.getAcquiredAt(), Instant.now());
        Duration remaining = leaseTime.minus(elapsed);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    @Override
    public boolean isLocked() {
        return lockMap.isLocked(identifier);
    }

    @Override
    public void forceUnlock() {
        log.warn("Force unlocking reservation: {}", identifier);
        lockMap.forceUnlock(identifier);
        try {
            // Best-effort cleanup of the debug value; zero timeout so we never block on a
            // holder that acquired the lock right after the force unlock.
            lockMap.tryRemove(identifier, 0, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("Failed to remove debug value during forceUnlock for {}: {}",
                identifier, e.getMessage());
        }
        holdTracker.remove(identifier);
    }

    @Override
    public void lock() {
        Instant start = Instant.now();
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    lockMap.lock(identifier, leaseTime.toMillis(), TimeUnit.MILLISECONDS);
                    break;
                } catch (Exception e) {
                    // Lock.lock() must keep waiting through interrupts; the client-side
                    // proxy surfaces them as HazelcastException(InterruptedException).
                    // Always clear the flag before retrying - a still-set flag would make
                    // the next lock() attempt fail immediately and this loop spin forever.
                    boolean flagWasSet = Thread.interrupted();
                    if (flagWasSet || causedByInterrupt(e)) {
                        interrupted = true;
                        continue;
                    }
                    throw e;
                }
            }
            recordAcquired(start);

            log.debug("Acquired reservation: {}", identifier);

        } catch (Exception e) {
            recordError(start);

            throw new ReservationAcquisitionException(domain, identifier,
                "Failed to acquire reservation", e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }

        Instant start = Instant.now();
        try {
            while (!lockMap.tryLock(identifier, INTERRUPT_POLL_MILLIS, TimeUnit.MILLISECONDS,
                    leaseTime.toMillis(), TimeUnit.MILLISECONDS)) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
            }
            recordAcquired(start);

            log.debug("Acquired reservation (interruptibly): {}", identifier);

        } catch (InterruptedException e) {
            metrics.recordAcquisition(domain, Duration.between(start, Instant.now()), "interrupted");
            throw e;
        } catch (Exception e) {
            // The client-side proxy wraps interrupts in HazelcastException instead of
            // throwing InterruptedException like the member-side proxy does
            if (causedByInterrupt(e) || Thread.interrupted()) {
                metrics.recordAcquisition(domain, Duration.between(start, Instant.now()), "interrupted");
                throw interruptedException(e);
            }
            recordError(start);

            throw new ReservationAcquisitionException(domain, identifier,
                "Failed to acquire reservation", e);
        }
    }

    @Override
    public boolean tryLock() {
        Instant start = Instant.now();
        try {
            boolean acquired = lockMap.tryLock(identifier, 0, TimeUnit.MILLISECONDS,
                leaseTime.toMillis(), TimeUnit.MILLISECONDS);

            if (acquired) {
                recordAcquired(start);

                log.debug("Try-locked reservation: {}", identifier);
            } else {
                metrics.recordAcquisition(domain, Duration.between(start, Instant.now()), "unavailable");
                metrics.recordAcquisitionAttempt(domain, false);

                log.debug("Try-lock failed, reservation unavailable: {}", identifier);
            }

            return acquired;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.recordAcquisition(domain, Duration.between(start, Instant.now()), "interrupted");
            return false;
        } catch (Exception e) {
            if (causedByInterrupt(e)) {
                Thread.currentThread().interrupt();
                metrics.recordAcquisition(domain, Duration.between(start, Instant.now()), "interrupted");
                return false;
            }
            recordError(start);
            log.warn("Error during tryLock for {}: {}", identifier, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }

        Instant start = Instant.now();
        try {
            boolean acquired = lockMap.tryLock(identifier, time, unit,
                leaseTime.toMillis(), TimeUnit.MILLISECONDS);

            if (acquired) {
                recordAcquired(start);

                log.debug("Try-locked reservation with timeout: {}", identifier);
            } else {
                metrics.recordAcquisition(domain, Duration.between(start, Instant.now()), "timeout");
                metrics.recordAcquisitionAttempt(domain, false);

                log.debug("Try-lock timed out for reservation: {}", identifier);
            }

            return acquired;

        } catch (InterruptedException e) {
            metrics.recordAcquisition(domain, Duration.between(start, Instant.now()), "interrupted");
            throw e;
        } catch (Exception e) {
            if (causedByInterrupt(e) || Thread.interrupted()) {
                metrics.recordAcquisition(domain, Duration.between(start, Instant.now()), "interrupted");
                throw interruptedException(e);
            }
            throw e instanceof RuntimeException re
                ? re
                : new ReservationAcquisitionException(domain, identifier,
                    "Failed to acquire reservation", e);
        }
    }

    @Override
    public void unlock() {
        HoldTracker.Hold hold = holdTracker.get(identifier);
        if (hold == null || hold.getCount() == 0) {
            throw new IllegalMonitorStateException(
                "Current thread does not hold the reservation: " + identifier);
        }

        try {
            if (hold.getCount() == 1) {
                try {
                    // Remove the debug value while we still own the lock. Zero timeout so we
                    // never block on (or delete the value of) a holder that took over after
                    // our lease expired.
                    lockMap.tryRemove(identifier, 0, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    // Best-effort: a failed debug cleanup must not prevent the unlock below
                    log.debug("Failed to remove debug value during unlock for {}: {}",
                        identifier, e.getMessage());
                }
            }

            lockMap.unlock(identifier);

            hold.setCount(hold.getCount() - 1);
            if (hold.getCount() == 0) {
                metrics.recordHeldTime(domain, Duration.between(hold.getAcquiredAt(), Instant.now()));
                holdTracker.remove(identifier);
            }

            log.debug("Unlocked reservation: {}", identifier);

        } catch (IllegalMonitorStateException e) {
            // We tracked a hold but the cluster no longer recognizes it - the lease expired
            holdTracker.remove(identifier);
            metrics.recordExpiration(domain);

            log.warn("Unlock failed for reservation {} - lease expired", identifier);

            throw new ReservationExpiredException(domain, identifier);
        }
    }

    private void recordAcquired(Instant start) {
        storeDebugValue();

        HoldTracker.Hold hold = holdTracker.getOrCreate(identifier);
        if (hold.getCount() > 0 && leaseLapsed(hold)) {
            // The previous hold expired without an unlock, so this acquisition starts a
            // fresh cluster-side hold; carrying the stale count forward would make later
            // unlocks release more than was actually acquired.
            hold.setCount(0);
        }
        hold.setCount(hold.getCount() + 1);
        hold.setAcquiredAt(Instant.now());

        metrics.recordAcquisition(domain, Duration.between(start, Instant.now()), "acquired");
        metrics.recordAcquisitionAttempt(domain, true);
    }

    private boolean leaseLapsed(HoldTracker.Hold hold) {
        return Duration.between(hold.getAcquiredAt(), Instant.now()).compareTo(leaseTime) >= 0;
    }

    private void recordError(Instant start) {
        metrics.recordAcquisition(domain, Duration.between(start, Instant.now()), "error");
        metrics.recordAcquisitionAttempt(domain, false);
    }

    private void storeDebugValue() {
        // Best-effort: the lock is already held at this point, so a failed debug write
        // must not make the acquisition look failed - the caller would never unlock,
        // leaking the lock until lease expiry. Also refreshes the entry TTL on
        // reentrant acquisition.
        try {
            lockMap.set(identifier, buildDebugValue(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("Failed to store debug value for {}: {}", identifier, e.getMessage());
        }
    }

    private static boolean causedByInterrupt(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    private InterruptedException interruptedException(Exception cause) {
        InterruptedException ie = new InterruptedException(
            "Interrupted while acquiring reservation: " + identifier);
        ie.initCause(cause);
        return ie;
    }

    private String buildDebugValue() {
        String threadName = Thread.currentThread().getName();
        Instant now = Instant.now();

        return String.format("holder=%s@%s,acquired=%s", threadName, HOST_NAME, now);
    }

    private static String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
