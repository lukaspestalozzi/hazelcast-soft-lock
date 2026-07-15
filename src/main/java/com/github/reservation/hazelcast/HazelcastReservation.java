package com.github.reservation.hazelcast;

import com.github.reservation.Reservation;
import com.github.reservation.ReservationAcquisitionException;
import com.github.reservation.ReservationExpiredException;
import com.github.reservation.ReservationReleaseException;
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
    // slices and checks the interrupt flag between attempts. The slice starts short so
    // interrupts are noticed quickly and backs off to a cap, limiting per-waiter network
    // chatter on the client topology where every slice is a remote call.
    private static final long INTERRUPT_POLL_INITIAL_MILLIS = 100;
    private static final long INTERRUPT_POLL_MAX_MILLIS = 1_000;

    // The stale-hold heuristic compares the local clock against a lease the cluster
    // measures from an earlier instant (the server granted the lock before its response
    // reached us), so the two can never agree exactly. The margin biases the comparison
    // toward over-detecting expiry: mis-detection then leaks a reentrant hold until the
    // lease expires (loud, self-healing) instead of silently releasing a lock the caller
    // still believes it holds. The margin must stay well below the lease itself or every
    // reentrant acquisition would look stale.
    private static final Duration STALE_HOLD_MAX_MARGIN = Duration.ofMillis(500);

    // Resolved once; a DNS lookup per lock operation is needless overhead
    private static final String HOST_NAME = resolveHostName();

    private final IMap<String, String> lockMap;
    private final String domain;
    private final String identifier;
    private final Duration leaseTime;
    private final Duration staleHoldMargin;
    private final boolean debugValues;
    private final ReservationMetrics metrics;
    private final HoldTracker holdTracker;

    HazelcastReservation(
            IMap<String, String> lockMap,
            String domain,
            String identifier,
            Duration leaseTime,
            boolean debugValues,
            ReservationMetrics metrics,
            HoldTracker holdTracker) {
        this.lockMap = lockMap;
        this.domain = domain;
        this.identifier = identifier;
        this.leaseTime = leaseTime;
        this.staleHoldMargin = min(STALE_HOLD_MAX_MARGIN, leaseTime.dividedBy(5));
        this.debugValues = debugValues;
        this.metrics = metrics;
        this.holdTracker = holdTracker;
    }

    @Override
    public String getIdentifier() {
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
    public boolean isHeldByCurrentThread() {
        HoldTracker.Hold hold = holdTracker.get(identifier);
        return hold != null && hold.getCount() > 0 && !leaseLapsed(hold);
    }

    @Override
    public void forceUnlock() {
        log.warn("Force unlocking reservation: {}", identifier);
        lockMap.forceUnlock(identifier);
        removeDebugValue("forceUnlock");
        // Only the calling thread's local hold state can be cleared here; holds tracked
        // by other threads (in this or another process) go stale and surface as
        // ReservationExpiredException on their next unlock.
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
        long pollMillis = INTERRUPT_POLL_INITIAL_MILLIS;
        try {
            while (!lockMap.tryLock(identifier, pollMillis, TimeUnit.MILLISECONDS,
                    leaseTime.toMillis(), TimeUnit.MILLISECONDS)) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                pollMillis = Math.min(pollMillis * 2, INTERRUPT_POLL_MAX_MILLIS);
            }
            recordAcquired(start);

            log.debug("Acquired reservation (interruptibly): {}", identifier);

        } catch (InterruptedException e) {
            metrics.recordAcquisition(Duration.between(start, Instant.now()), "interrupted");
            throw e;
        } catch (Exception e) {
            // The client-side proxy wraps interrupts in HazelcastException instead of
            // throwing InterruptedException like the member-side proxy does
            if (causedByInterrupt(e) || Thread.interrupted()) {
                metrics.recordAcquisition(Duration.between(start, Instant.now()), "interrupted");
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
                metrics.recordAcquisition(Duration.between(start, Instant.now()), "unavailable");

                log.debug("Try-lock failed, reservation unavailable: {}", identifier);
            }

            return acquired;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.recordAcquisition(Duration.between(start, Instant.now()), "interrupted");
            return false;
        } catch (Exception e) {
            if (causedByInterrupt(e)) {
                Thread.currentThread().interrupt();
                metrics.recordAcquisition(Duration.between(start, Instant.now()), "interrupted");
                return false;
            }
            recordError(start);
            throw new ReservationAcquisitionException(domain, identifier,
                "Failed to acquire reservation", e);
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
                metrics.recordAcquisition(Duration.between(start, Instant.now()), "timeout");

                log.debug("Try-lock timed out for reservation: {}", identifier);
            }

            return acquired;

        } catch (InterruptedException e) {
            metrics.recordAcquisition(Duration.between(start, Instant.now()), "interrupted");
            throw e;
        } catch (Exception e) {
            if (causedByInterrupt(e) || Thread.interrupted()) {
                metrics.recordAcquisition(Duration.between(start, Instant.now()), "interrupted");
                throw interruptedException(e);
            }
            recordError(start);
            throw new ReservationAcquisitionException(domain, identifier,
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

        if (hold.getCount() == 1) {
            // Remove the debug value while we still own the lock (best-effort).
            removeDebugValue("unlock");
        }

        try {
            lockMap.unlock(identifier);
        } catch (IllegalMonitorStateException e) {
            // We tracked a hold but the cluster no longer recognizes it. The lease most
            // likely expired, but the reservation may also have been force-released or
            // ownership lost through a cluster event; the cluster cannot tell us which.
            holdTracker.remove(identifier);
            metrics.recordExpiration();

            log.warn("Unlock failed for reservation {} - ownership was already lost "
                + "(lease expiry or force-release)", identifier);

            throw new ReservationExpiredException(domain, identifier);
        } catch (Exception e) {
            // Infrastructure failure: the cluster-side outcome is unknown, so the local
            // hold is kept. The caller may retry unlock(); at worst the lease expires.
            throw new ReservationReleaseException(domain, identifier,
                "Failed to release reservation", e);
        }

        hold.decrement();
        if (hold.getCount() == 0) {
            Instant acquiredAt = hold.getAcquiredAt();
            holdTracker.remove(identifier);
            metrics.recordHeldTime(Duration.between(acquiredAt, Instant.now()));
        }

        log.debug("Unlocked reservation: {}", identifier);
    }

    private void recordAcquired(Instant start) {
        storeDebugValue();

        HoldTracker.Hold hold = holdTracker.getOrCreate(identifier);
        if (hold.getCount() > 0 && leaseLapsed(hold)) {
            // The previous hold (likely) expired without an unlock, so this acquisition
            // starts a fresh cluster-side hold; carrying the stale count forward would
            // make later unlocks release more than was actually acquired.
            hold.setCount(0);
        }
        hold.increment();
        hold.setAcquiredAt(Instant.now());

        metrics.recordAcquisition(Duration.between(start, Instant.now()), "acquired");
    }

    private boolean leaseLapsed(HoldTracker.Hold hold) {
        Duration elapsed = Duration.between(hold.getAcquiredAt(), Instant.now());
        return elapsed.compareTo(leaseTime.minus(staleHoldMargin)) >= 0;
    }

    private void recordError(Instant start) {
        metrics.recordAcquisition(Duration.between(start, Instant.now()), "error");
    }

    private void storeDebugValue() {
        if (!debugValues) {
            return;
        }
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

    private void removeDebugValue(String operation) {
        if (!debugValues) {
            return;
        }
        try {
            // Best-effort cleanup with zero timeout so we never block on (or delete the
            // value of) a holder that took over after our lease expired or after a
            // force unlock.
            lockMap.tryRemove(identifier, 0, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("Failed to remove debug value during {} for {}: {}",
                operation, identifier, e.getMessage());
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

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
