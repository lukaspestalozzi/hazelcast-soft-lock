package com.github.reservation.hazelcast;

import com.github.reservation.Reservation;
import com.github.reservation.ReservationAcquisitionException;
import com.github.reservation.ReservationExpiredException;
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

    private final IMap<String, String> lockMap;
    private final String domain;
    private final String identifier;
    private final Duration leaseTime;
    private final ReservationMetrics metrics;

    // Track when we acquired the lock for metrics
    private volatile Instant acquiredAt;

    HazelcastReservation(
            IMap<String, String> lockMap,
            String domain,
            String identifier,
            Duration leaseTime,
            ReservationMetrics metrics) {
        this.lockMap = lockMap;
        this.domain = domain;
        this.identifier = identifier;
        this.leaseTime = leaseTime;
        this.metrics = metrics;
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
        if (acquiredAt == null) {
            return Duration.ZERO;
        }
        Duration elapsed = Duration.between(acquiredAt, Instant.now());
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
        lockMap.remove(identifier);
        lockMap.forceUnlock(identifier);
        acquiredAt = null;
    }

    @Override
    public void lock() {
        Instant start = Instant.now();
        try {
            // Acquire the Hazelcast lock with lease time
            lockMap.lock(identifier, leaseTime.toMillis(), TimeUnit.MILLISECONDS);
            acquiredAt = Instant.now();

            // Store debug value
            String value = buildDebugValue();
            lockMap.set(identifier, value, leaseTime.toMillis(), TimeUnit.MILLISECONDS);

            Duration elapsed = Duration.between(start, Instant.now());
            metrics.recordAcquisition(domain, elapsed, "acquired");
            metrics.recordAcquisitionAttempt(domain, true);

            log.debug("Acquired reservation: {}", identifier);

        } catch (Exception e) {
            Duration elapsed = Duration.between(start, Instant.now());
            metrics.recordAcquisition(domain, elapsed, "error");
            metrics.recordAcquisitionAttempt(domain, false);

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
            // Hazelcast's lock() is interruptible
            lockMap.lock(identifier, leaseTime.toMillis(), TimeUnit.MILLISECONDS);
            acquiredAt = Instant.now();

            String value = buildDebugValue();
            lockMap.set(identifier, value, leaseTime.toMillis(), TimeUnit.MILLISECONDS);

            Duration elapsed = Duration.between(start, Instant.now());
            metrics.recordAcquisition(domain, elapsed, "acquired");
            metrics.recordAcquisitionAttempt(domain, true);

            log.debug("Acquired reservation (interruptibly): {}", identifier);

        } catch (Exception e) {
            Duration elapsed = Duration.between(start, Instant.now());

            if (e instanceof InterruptedException) {
                metrics.recordAcquisition(domain, elapsed, "interrupted");
                throw (InterruptedException) e;
            }

            metrics.recordAcquisition(domain, elapsed, "error");
            metrics.recordAcquisitionAttempt(domain, false);

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
                acquiredAt = Instant.now();
                String value = buildDebugValue();
                lockMap.set(identifier, value, leaseTime.toMillis(), TimeUnit.MILLISECONDS);

                Duration elapsed = Duration.between(start, Instant.now());
                metrics.recordAcquisition(domain, elapsed, "acquired");
                metrics.recordAcquisitionAttempt(domain, true);

                log.debug("Try-locked reservation: {}", identifier);
            } else {
                Duration elapsed = Duration.between(start, Instant.now());
                metrics.recordAcquisition(domain, elapsed, "unavailable");
                metrics.recordAcquisitionAttempt(domain, false);

                log.debug("Try-lock failed, reservation unavailable: {}", identifier);
            }

            return acquired;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
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

            Duration elapsed = Duration.between(start, Instant.now());

            if (acquired) {
                acquiredAt = Instant.now();
                String value = buildDebugValue();
                lockMap.set(identifier, value, leaseTime.toMillis(), TimeUnit.MILLISECONDS);

                metrics.recordAcquisition(domain, elapsed, "acquired");
                metrics.recordAcquisitionAttempt(domain, true);

                log.debug("Try-locked reservation with timeout: {}", identifier);
            } else {
                metrics.recordAcquisition(domain, elapsed, "timeout");
                metrics.recordAcquisitionAttempt(domain, false);

                log.debug("Try-lock timed out for reservation: {}", identifier);
            }

            return acquired;

        } catch (InterruptedException e) {
            Duration elapsed = Duration.between(start, Instant.now());
            metrics.recordAcquisition(domain, elapsed, "interrupted");
            throw e;
        }
    }

    @Override
    public void unlock() {
        try {
            // Remove the debug value first
            lockMap.remove(identifier);

            // Then release the lock
            lockMap.unlock(identifier);

            if (acquiredAt != null) {
                Duration heldTime = Duration.between(acquiredAt, Instant.now());
                metrics.recordHeldTime(domain, heldTime);
            }
            acquiredAt = null;

            log.debug("Unlocked reservation: {}", identifier);

        } catch (IllegalMonitorStateException e) {
            // Lock expired or not owned by this thread
            metrics.recordExpiration(domain);
            acquiredAt = null;

            log.warn("Unlock failed for reservation {} - likely expired", identifier);

            throw new ReservationExpiredException(domain, identifier);
        }
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
