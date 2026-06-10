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
        // Best-effort cleanup of the debug value; zero timeout so we never block on a
        // holder that acquired the lock right after the force unlock.
        lockMap.tryRemove(identifier, 0, TimeUnit.MILLISECONDS);
        holdTracker.remove(identifier);
    }

    @Override
    public void lock() {
        Instant start = Instant.now();
        try {
            lockMap.lock(identifier, leaseTime.toMillis(), TimeUnit.MILLISECONDS);
            recordAcquired(start);

            log.debug("Acquired reservation: {}", identifier);

        } catch (Exception e) {
            recordError(start);

            throw new ReservationAcquisitionException(domain, identifier,
                "Failed to acquire reservation", e);
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
                // Remove the debug value while we still own the lock. Zero timeout so we
                // never block on (or delete the value of) a holder that took over after
                // our lease expired.
                lockMap.tryRemove(identifier, 0, TimeUnit.MILLISECONDS);
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
        // Store debug value (refreshes the entry TTL on reentrant acquisition)
        lockMap.set(identifier, buildDebugValue(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);

        HoldTracker.Hold hold = holdTracker.getOrCreate(identifier);
        hold.setCount(hold.getCount() + 1);
        hold.setAcquiredAt(Instant.now());

        metrics.recordAcquisition(domain, Duration.between(start, Instant.now()), "acquired");
        metrics.recordAcquisitionAttempt(domain, true);
    }

    private void recordError(Instant start) {
        metrics.recordAcquisition(domain, Duration.between(start, Instant.now()), "error");
        metrics.recordAcquisitionAttempt(domain, false);
    }

    private String buildDebugValue() {
        String threadName = Thread.currentThread().getName();
        String hostName = getHostName();
        Instant now = Instant.now();

        return String.format("holder=%s@%s,acquired=%s", threadName, hostName, now);
    }

    private static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
