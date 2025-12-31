package com.github.reservation.oracle;

import com.github.reservation.Reservation;
import com.github.reservation.ReservationAcquisitionException;
import com.github.reservation.ReservationExpiredException;
import com.github.reservation.internal.ReservationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Oracle/JDBC-backed implementation of {@link Reservation}.
 */
final class OracleReservation implements Reservation {

    private static final Logger log = LoggerFactory.getLogger(OracleReservation.class);

    private static final Duration INITIAL_POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration MAX_POLL_INTERVAL = Duration.ofSeconds(1);

    private final LockingStrategy lockingStrategy;
    private final String domain;
    private final String identifier;
    private final String reservationKey;
    private final Duration leaseTime;
    private final ReservationMetrics metrics;

    // Track the holder ID for this thread
    private final ThreadLocal<String> currentHolder = new ThreadLocal<>();
    private final ThreadLocal<Instant> acquiredAt = new ThreadLocal<>();
    private final ThreadLocal<Integer> lockCount = ThreadLocal.withInitial(() -> 0);

    OracleReservation(
            LockingStrategy lockingStrategy,
            String domain,
            String identifier,
            String reservationKey,
            Duration leaseTime,
            ReservationMetrics metrics) {
        this.lockingStrategy = lockingStrategy;
        this.domain = domain;
        this.identifier = identifier;
        this.reservationKey = reservationKey;
        this.leaseTime = leaseTime;
        this.metrics = metrics;
    }

    @Override
    public String getDomain() {
        return domain;
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String getReservationKey() {
        return reservationKey;
    }

    @Override
    public Duration getRemainingLeaseTime() {
        try {
            Optional<LockingStrategy.LockInfo> info = lockingStrategy.getLockInfo(reservationKey);
            return info.map(LockingStrategy.LockInfo::remainingLeaseTime).orElse(Duration.ZERO);
        } catch (LockingException e) {
            log.warn("Failed to get remaining lease time for {}: {}", reservationKey, e.getMessage());
            return Duration.ZERO;
        }
    }

    @Override
    public boolean isLocked() {
        try {
            return lockingStrategy.isLocked(reservationKey);
        } catch (LockingException e) {
            log.warn("Failed to check lock status for {}: {}", reservationKey, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isHeldByCurrentThread() {
        String holder = currentHolder.get();
        if (holder == null) {
            return false;
        }

        try {
            Optional<LockingStrategy.LockInfo> info = lockingStrategy.getLockInfo(reservationKey);
            return info.map(i -> holder.equals(i.holder())).orElse(false);
        } catch (LockingException e) {
            log.warn("Failed to check lock ownership for {}: {}", reservationKey, e.getMessage());
            return false;
        }
    }

    @Override
    public void forceUnlock() {
        log.warn("Force unlocking reservation: {}", reservationKey);
        try {
            lockingStrategy.forceRelease(reservationKey);
            currentHolder.remove();
            acquiredAt.remove();
            lockCount.set(0);
        } catch (LockingException e) {
            log.error("Failed to force unlock {}: {}", reservationKey, e.getMessage());
        }
    }

    @Override
    public void lock() {
        // Check for reentrancy
        if (isHeldByCurrentThread()) {
            lockCount.set(lockCount.get() + 1);
            log.debug("Reentrant lock acquired: {} (count={})", reservationKey, lockCount.get());
            return;
        }

        String holder = buildHolder();
        Instant start = Instant.now();
        Duration pollInterval = INITIAL_POLL_INTERVAL;

        while (true) {
            try {
                if (lockingStrategy.tryAcquire(reservationKey, holder, leaseTime)) {
                    currentHolder.set(holder);
                    acquiredAt.set(Instant.now());
                    lockCount.set(1);

                    Duration elapsed = Duration.between(start, Instant.now());
                    metrics.recordAcquisition(domain, elapsed, "acquired");
                    metrics.recordAcquisitionAttempt(domain, true);

                    log.debug("Acquired reservation: {}", reservationKey);
                    return;
                }

                // Lock not available, wait and retry
                Thread.sleep(pollInterval.toMillis());
                pollInterval = pollInterval.multipliedBy(2);
                if (pollInterval.compareTo(MAX_POLL_INTERVAL) > 0) {
                    pollInterval = MAX_POLL_INTERVAL;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Duration elapsed = Duration.between(start, Instant.now());
                metrics.recordAcquisition(domain, elapsed, "interrupted");

                throw new ReservationAcquisitionException(domain, identifier,
                    "Interrupted while waiting for reservation", e);
            } catch (LockingException e) {
                Duration elapsed = Duration.between(start, Instant.now());
                metrics.recordAcquisition(domain, elapsed, "error");
                metrics.recordAcquisitionAttempt(domain, false);

                throw new ReservationAcquisitionException(domain, identifier,
                    "Database error acquiring reservation", e);
            }
        }
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }

        // Check for reentrancy
        if (isHeldByCurrentThread()) {
            lockCount.set(lockCount.get() + 1);
            log.debug("Reentrant lock acquired (interruptibly): {} (count={})", reservationKey, lockCount.get());
            return;
        }

        String holder = buildHolder();
        Instant start = Instant.now();
        Duration pollInterval = INITIAL_POLL_INTERVAL;

        while (true) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }

            try {
                if (lockingStrategy.tryAcquire(reservationKey, holder, leaseTime)) {
                    currentHolder.set(holder);
                    acquiredAt.set(Instant.now());
                    lockCount.set(1);

                    Duration elapsed = Duration.between(start, Instant.now());
                    metrics.recordAcquisition(domain, elapsed, "acquired");
                    metrics.recordAcquisitionAttempt(domain, true);

                    log.debug("Acquired reservation (interruptibly): {}", reservationKey);
                    return;
                }

                Thread.sleep(pollInterval.toMillis());
                pollInterval = pollInterval.multipliedBy(2);
                if (pollInterval.compareTo(MAX_POLL_INTERVAL) > 0) {
                    pollInterval = MAX_POLL_INTERVAL;
                }

            } catch (InterruptedException e) {
                Duration elapsed = Duration.between(start, Instant.now());
                metrics.recordAcquisition(domain, elapsed, "interrupted");
                throw e;
            } catch (LockingException e) {
                Duration elapsed = Duration.between(start, Instant.now());
                metrics.recordAcquisition(domain, elapsed, "error");
                metrics.recordAcquisitionAttempt(domain, false);

                throw new ReservationAcquisitionException(domain, identifier,
                    "Database error acquiring reservation", e);
            }
        }
    }

    @Override
    public boolean tryLock() {
        // Check for reentrancy
        if (isHeldByCurrentThread()) {
            lockCount.set(lockCount.get() + 1);
            log.debug("Reentrant tryLock acquired: {} (count={})", reservationKey, lockCount.get());
            return true;
        }

        String holder = buildHolder();
        Instant start = Instant.now();

        try {
            boolean acquired = lockingStrategy.tryAcquire(reservationKey, holder, leaseTime);

            Duration elapsed = Duration.between(start, Instant.now());

            if (acquired) {
                currentHolder.set(holder);
                acquiredAt.set(Instant.now());
                lockCount.set(1);

                metrics.recordAcquisition(domain, elapsed, "acquired");
                metrics.recordAcquisitionAttempt(domain, true);

                log.debug("Try-locked reservation: {}", reservationKey);
            } else {
                metrics.recordAcquisition(domain, elapsed, "unavailable");
                metrics.recordAcquisitionAttempt(domain, false);

                log.debug("Try-lock failed, reservation unavailable: {}", reservationKey);
            }

            return acquired;

        } catch (LockingException e) {
            log.warn("Error during tryLock for {}: {}", reservationKey, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }

        // Check for reentrancy
        if (isHeldByCurrentThread()) {
            lockCount.set(lockCount.get() + 1);
            log.debug("Reentrant tryLock (timed) acquired: {} (count={})", reservationKey, lockCount.get());
            return true;
        }

        String holder = buildHolder();
        Instant start = Instant.now();
        long deadlineNanos = System.nanoTime() + unit.toNanos(time);
        Duration pollInterval = INITIAL_POLL_INTERVAL;

        while (System.nanoTime() < deadlineNanos) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }

            try {
                if (lockingStrategy.tryAcquire(reservationKey, holder, leaseTime)) {
                    currentHolder.set(holder);
                    acquiredAt.set(Instant.now());
                    lockCount.set(1);

                    Duration elapsed = Duration.between(start, Instant.now());
                    metrics.recordAcquisition(domain, elapsed, "acquired");
                    metrics.recordAcquisitionAttempt(domain, true);

                    log.debug("Try-locked reservation with timeout: {}", reservationKey);
                    return true;
                }

                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    break;
                }

                long sleepMillis = Math.min(pollInterval.toMillis(),
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                if (sleepMillis > 0) {
                    Thread.sleep(sleepMillis);
                }

                pollInterval = pollInterval.multipliedBy(2);
                if (pollInterval.compareTo(MAX_POLL_INTERVAL) > 0) {
                    pollInterval = MAX_POLL_INTERVAL;
                }

            } catch (InterruptedException e) {
                Duration elapsed = Duration.between(start, Instant.now());
                metrics.recordAcquisition(domain, elapsed, "interrupted");
                throw e;
            } catch (LockingException e) {
                log.warn("Error during tryLock for {}: {}", reservationKey, e.getMessage());
                // Continue trying until timeout
            }
        }

        Duration elapsed = Duration.between(start, Instant.now());
        metrics.recordAcquisition(domain, elapsed, "timeout");
        metrics.recordAcquisitionAttempt(domain, false);

        log.debug("Try-lock timed out for reservation: {}", reservationKey);
        return false;
    }

    @Override
    public void unlock() {
        String holder = currentHolder.get();
        if (holder == null) {
            throw new IllegalMonitorStateException("Current thread does not hold the reservation: " + reservationKey);
        }

        // Handle reentrancy
        int count = lockCount.get();
        if (count > 1) {
            lockCount.set(count - 1);
            log.debug("Reentrant unlock: {} (count={})", reservationKey, count - 1);
            return;
        }

        try {
            boolean released = lockingStrategy.release(reservationKey, holder);

            if (released) {
                Instant acquired = acquiredAt.get();
                if (acquired != null) {
                    Duration heldTime = Duration.between(acquired, Instant.now());
                    metrics.recordHeldTime(domain, heldTime);
                }

                currentHolder.remove();
                acquiredAt.remove();
                lockCount.set(0);

                log.debug("Unlocked reservation: {}", reservationKey);
            } else {
                // Lock was not found or was held by someone else (expired)
                currentHolder.remove();
                acquiredAt.remove();
                lockCount.set(0);

                metrics.recordExpiration(domain);

                log.warn("Unlock failed for reservation {} - likely expired", reservationKey);

                throw new ReservationExpiredException(domain, identifier);
            }
        } catch (LockingException e) {
            currentHolder.remove();
            acquiredAt.remove();
            lockCount.set(0);

            throw new ReservationExpiredException(domain, identifier);
        }
    }

    private String buildHolder() {
        String threadName = Thread.currentThread().getName();
        String threadId = String.valueOf(Thread.currentThread().threadId());
        String hostName = getHostName();
        return threadName + "-" + threadId + "@" + hostName;
    }

    private static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
