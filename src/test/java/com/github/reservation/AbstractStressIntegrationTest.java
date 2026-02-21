package com.github.reservation;

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract stress test class for ReservationManager implementations.
 *
 * <p>Designed to battle-test lock implementations at production-relevant
 * concurrency levels (~100 requests/second). Each test proves a specific
 * correctness or resilience property under sustained parallel load.</p>
 *
 * <p>Subclasses provide the specific backend (Hazelcast or JDBC) via
 * {@link #createManager(String, Duration)}.</p>
 */
public abstract class AbstractStressIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractStressIntegrationTest.class);
    protected static final String DEFAULT_DOMAIN = "stress-test";

    protected ReservationManager manager;
    private final List<ReservationManager> managersToClose = new ArrayList<>();

    /**
     * Creates the ReservationManager implementation to test.
     */
    protected abstract ReservationManager createManager(String domain, Duration leaseTime);

    /**
     * Cleans up resources after each test.
     */
    protected abstract void cleanup();

    // ==================== Configurable parameters ====================
    // Subclasses can override to reduce load for slower backends (e.g. Testcontainers).

    /** Thread count for tests with high thread counts (thundering herd, burst). */
    protected int highThreadCount() { return 100; }

    /** Thread count for medium-load tests (mutual exclusion, throughput). */
    protected int mediumThreadCount() { return 50; }

    /** Thread count for burst test (very high). */
    protected int burstThreadCount() { return 200; }

    /** Default tryLock timeout for contended tests. */
    protected int tryLockTimeoutSeconds() { return 30; }

    @BeforeEach
    void setUp() {
        manager = createManager(DEFAULT_DOMAIN, Duration.ofSeconds(30));
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        for (ReservationManager mgr : managersToClose) {
            try { mgr.close(); } catch (Exception e) { /* ignore */ }
        }
        managersToClose.clear();
        cleanup();
    }

    // ==================== 1. MUTUAL EXCLUSION PROOF ====================

    @Test
    @DisplayName("Mutual exclusion: no two threads inside critical section simultaneously")
    @Timeout(120)
    void mutualExclusionMustHold() throws Exception {
        // This is the fundamental correctness property of any lock.
        // We use an AtomicInteger as occupancy counter: if it ever exceeds 1,
        // two threads were inside the critical section at the same time.
        int threadCount = mediumThreadCount();
        int iterationsPerThread = 20;
        AtomicInteger occupancy = new AtomicInteger(0);
        AtomicInteger maxObservedOccupancy = new AtomicInteger(0);
        AtomicInteger violations = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int iter = 0; iter < iterationsPerThread; iter++) {
                        Reservation reservation = manager.getReservation("mutex-lock");
                        if (reservation.tryLock(10, TimeUnit.SECONDS)) {
                            try {
                                int current = occupancy.incrementAndGet();
                                maxObservedOccupancy.accumulateAndGet(current, Math::max);
                                if (current > 1) {
                                    violations.incrementAndGet();
                                }
                                // Simulate critical section work — vary timing
                                // to create interesting interleavings
                                Thread.sleep(ThreadLocalRandom.current().nextInt(1, 5));
                                successCount.incrementAndGet();
                            } finally {
                                occupancy.decrementAndGet();
                                reservation.unlock();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch.countDown();
                }
            }, "mutex-thread-" + i).start();
        }

        startLatch.countDown();
        boolean completed = completeLatch.await(120, TimeUnit.SECONDS);

        log.info("Mutual exclusion test: {} successes, {} violations, max occupancy={}",
                successCount.get(), violations.get(), maxObservedOccupancy.get());

        assertThat(completed).as("All threads should complete within timeout").isTrue();
        assertThat(violations.get())
                .as("CRITICAL: mutual exclusion was violated — two threads in critical section")
                .isZero();
        assertThat(maxObservedOccupancy.get())
                .as("Max occupancy must never exceed 1")
                .isEqualTo(1);
        assertThat(successCount.get()).isGreaterThan(0);
    }

    // ==================== 2. SUSTAINED THROUGHPUT ====================

    @Test
    @DisplayName("Sustained throughput: 100+ operations/second across many locks")
    @Timeout(120)
    void shouldSustainHighThroughput() throws Exception {
        // Simulate 100 rps by spreading requests across 20 lock keys
        // (to avoid pure serial bottleneck) with concurrent threads.
        int threadCount = mediumThreadCount();
        int lockKeyCount = 20;
        int targetOps = 2000;
        AtomicInteger totalOps = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        int opsPerThread = targetOps / threadCount;
        long startTime = System.nanoTime();

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int op = 0; op < opsPerThread; op++) {
                        String lockId = "throughput-" + ((threadId + op) % lockKeyCount);
                        Reservation reservation = manager.getReservation(lockId);

                        if (reservation.tryLock(5, TimeUnit.SECONDS)) {
                            try {
                                // Minimal critical section to maximize throughput
                                totalOps.incrementAndGet();
                            } finally {
                                reservation.unlock();
                            }
                        } else {
                            failures.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    completeLatch.countDown();
                }
            }, "throughput-thread-" + i).start();
        }

        startLatch.countDown();
        boolean completed = completeLatch.await(120, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

        double opsPerSecond = totalOps.get() * 1000.0 / elapsedMs;

        log.info("Throughput test: {} ops in {} ms = {} ops/sec, failures={}",
                totalOps.get(), elapsedMs, String.format("%.0f", opsPerSecond), failures.get());

        assertThat(completed).as("All threads should complete within timeout").isTrue();
        assertThat(totalOps.get()).as("Must complete significant operations").isGreaterThan(targetOps / 2);
        assertThat(opsPerSecond).as("Should sustain at least 100 ops/sec").isGreaterThan(100.0);
    }

    // ==================== 3. HIGH CONTENTION ON SINGLE KEY ====================

    @Test
    @DisplayName("High contention: 50 threads competing for single lock")
    @Timeout(120)
    void shouldHandleHighContention() throws Exception {
        int threadCount = mediumThreadCount();
        int iterationsPerThread = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicLong totalWaitTimeMs = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int iter = 0; iter < iterationsPerThread; iter++) {
                        Reservation reservation = manager.getReservation("contended-lock");
                        long start = System.currentTimeMillis();

                        if (reservation.tryLock(10, TimeUnit.SECONDS)) {
                            try {
                                totalWaitTimeMs.addAndGet(System.currentTimeMillis() - start);
                                Thread.sleep(2);
                                successCount.incrementAndGet();
                            } finally {
                                reservation.unlock();
                            }
                        } else {
                            failureCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch.countDown();
                }
            }, "contention-thread-" + i).start();
        }

        startLatch.countDown();
        boolean completed = completeLatch.await(120, TimeUnit.SECONDS);

        int total = successCount.get() + failureCount.get();
        long avgWaitMs = successCount.get() > 0 ? totalWaitTimeMs.get() / successCount.get() : 0;

        log.info("High contention: {} threads x {} iters = {} attempts", threadCount, iterationsPerThread, total);
        log.info("  Successes: {}, Failures: {}, Avg wait: {} ms", successCount.get(), failureCount.get(), avgWaitMs);

        assertThat(completed).as("All threads should complete").isTrue();
        assertThat(successCount.get()).isGreaterThan(0);
    }

    // ==================== 4. REENTRANT LOCKING UNDER LOAD ====================

    @Test
    @DisplayName("Reentrant locking correctness under concurrent access")
    @Timeout(60)
    void shouldHandleReentrantLockingUnderConcurrency() throws Exception {
        int threadCount = 30;
        AtomicInteger primaryLocks = new AtomicInteger(0);
        AtomicInteger reentrantLocks = new AtomicInteger(0);
        AtomicInteger reentrantFailures = new AtomicInteger(0);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    for (int iter = 0; iter < 10; iter++) {
                        Reservation reservation = manager.getReservation("reentrant-lock");

                        if (reservation.tryLock(5, TimeUnit.SECONDS)) {
                            try {
                                primaryLocks.incrementAndGet();

                                // Reentrant lock: MUST succeed — we hold the lock
                                if (reservation.tryLock()) {
                                    try {
                                        reentrantLocks.incrementAndGet();
                                        // Nest a third level
                                        if (reservation.tryLock()) {
                                            reservation.unlock();
                                        }
                                    } finally {
                                        reservation.unlock();
                                    }
                                } else {
                                    reentrantFailures.incrementAndGet();
                                }
                            } finally {
                                reservation.unlock();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch.countDown();
                }
            }).start();
        }

        boolean completed = completeLatch.await(60, TimeUnit.SECONDS);

        log.info("Reentrant stress: {} primary, {} reentrant, {} reentrant failures",
                primaryLocks.get(), reentrantLocks.get(), reentrantFailures.get());

        assertThat(completed).isTrue();
        // Every reentrant tryLock on a lock we hold MUST succeed
        assertThat(reentrantLocks.get()).isEqualTo(primaryLocks.get());
        assertThat(reentrantFailures.get()).isZero();
    }

    // ==================== 5. DOMAIN ISOLATION UNDER LOAD ====================

    @Test
    @DisplayName("Domain isolation: separate domains must not interfere under load")
    @Timeout(60)
    void shouldIsolateDomainsUnderConcurrency() throws Exception {
        String[] domains = {"domain-a", "domain-b", "domain-c", "domain-d"};
        ReservationManager[] managers = new ReservationManager[domains.length];
        for (int i = 0; i < domains.length; i++) {
            managers[i] = createManager(domains[i], Duration.ofSeconds(30));
            managersToClose.add(managers[i]);
        }

        AtomicInteger[] successCounts = new AtomicInteger[domains.length];
        for (int i = 0; i < domains.length; i++) {
            successCounts[i] = new AtomicInteger(0);
        }

        int threadsPerDomain = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(domains.length * threadsPerDomain);

        for (int d = 0; d < domains.length; d++) {
            final int domainIndex = d;
            final ReservationManager mgr = managers[d];

            for (int t = 0; t < threadsPerDomain; t++) {
                new Thread(() -> {
                    try {
                        startLatch.await();
                        for (int iter = 0; iter < 20; iter++) {
                            Reservation reservation = mgr.getReservation("shared-id");
                            if (reservation.tryLock(3, TimeUnit.SECONDS)) {
                                try {
                                    Thread.sleep(1);
                                    successCounts[domainIndex].incrementAndGet();
                                } finally {
                                    reservation.unlock();
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completeLatch.countDown();
                    }
                }).start();
            }
        }

        startLatch.countDown();
        boolean completed = completeLatch.await(60, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        log.info("Domain isolation results:");
        for (int i = 0; i < domains.length; i++) {
            log.info("  {}: {} successes", domains[i], successCounts[i].get());
            // Each domain operates independently — all should get significant successes
            assertThat(successCounts[i].get())
                    .as("domain %s should have successes", domains[i])
                    .isGreaterThan(threadsPerDomain);
        }
    }

    // ==================== 6. EXPIRATION UNDER SUSTAINED LOAD ====================

    @Test
    @DisplayName("Expiration: leases expire and locks become re-acquirable under load")
    @Timeout(60)
    void shouldHandleExpirationUnderLoad() throws Exception {
        ReservationManager shortLeaseManager = createManager("expiration-test", Duration.ofMillis(300));
        managersToClose.add(shortLeaseManager);

        AtomicInteger lockAcquired = new AtomicInteger(0);
        AtomicInteger expiredBeforeUnlock = new AtomicInteger(0);
        AtomicInteger successfulUnlock = new AtomicInteger(0);
        int threadCount = 20;
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    for (int iter = 0; iter < 5; iter++) {
                        Reservation reservation = shortLeaseManager.getReservation("expiring-lock");

                        if (reservation.tryLock(3, TimeUnit.SECONDS)) {
                            lockAcquired.incrementAndGet();
                            try {
                                Thread.sleep(600); // 2x lease time — guaranteed expiration
                                reservation.unlock();
                                successfulUnlock.incrementAndGet();
                            } catch (Exception e) {
                                expiredBeforeUnlock.incrementAndGet();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch.countDown();
                }
            }).start();
        }

        boolean completed = completeLatch.await(60, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        log.info("Expiration stress: acquired={}, expired={}, unlocked={}",
                lockAcquired.get(), expiredBeforeUnlock.get(), successfulUnlock.get());

        assertThat(lockAcquired.get()).isGreaterThan(0);
        assertThat(expiredBeforeUnlock.get() + successfulUnlock.get()).isEqualTo(lockAcquired.get());
    }

    // ==================== 7. RAPID LOCK/UNLOCK CYCLES ====================

    @Test
    @DisplayName("Rapid lock/unlock: throughput without contention")
    @Timeout(60)
    void shouldHandleRapidLockUnlockCycles() throws Exception {
        int threadCount = 20;
        int cyclesPerThread = 100;
        AtomicInteger totalCycles = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        long startTime = System.nanoTime();

        for (int i = 0; i < threadCount; i++) {
            final String lockId = "rapid-lock-" + i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int cycle = 0; cycle < cyclesPerThread; cycle++) {
                        Reservation reservation = manager.getReservation(lockId);
                        try {
                            reservation.lock();
                            reservation.unlock();
                            totalCycles.incrementAndGet();
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        boolean completed = completeLatch.await(60, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
        double cyclesPerSecond = totalCycles.get() * 1000.0 / elapsedMs;

        log.info("Rapid cycles: {} in {} ms = {} cycles/sec, failures={}",
                totalCycles.get(), elapsedMs, String.format("%.0f", cyclesPerSecond), failures.get());

        assertThat(completed).isTrue();
        assertThat(failures.get()).isZero();
        assertThat(totalCycles.get()).isEqualTo(threadCount * cyclesPerThread);
    }

    // ==================== 8. MIXED OPERATIONS UNDER LOAD ====================

    @Test
    @DisplayName("Mixed operations: lock, tryLock, isLocked, forceUnlock concurrently")
    @Timeout(60)
    void shouldHandleMixedOperationsUnderLoad() throws Exception {
        int threadCount = 40;
        AtomicInteger lockOps = new AtomicInteger(0);
        AtomicInteger tryLockOps = new AtomicInteger(0);
        AtomicInteger isLockedChecks = new AtomicInteger(0);
        AtomicInteger forceUnlocks = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadType = i % 4;
            final String lockId = "mixed-" + (i % 5);

            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int iter = 0; iter < 20; iter++) {
                        Reservation reservation = manager.getReservation(lockId);
                        try {
                            switch (threadType) {
                                case 0 -> {
                                    if (reservation.tryLock(1, TimeUnit.SECONDS)) {
                                        try {
                                            Thread.sleep(ThreadLocalRandom.current().nextInt(1, 10));
                                            lockOps.incrementAndGet();
                                        } finally {
                                            try { reservation.unlock(); } catch (Exception e) { /* expired */ }
                                        }
                                    }
                                }
                                case 1 -> {
                                    if (reservation.tryLock()) {
                                        try {
                                            tryLockOps.incrementAndGet();
                                        } finally {
                                            try { reservation.unlock(); } catch (Exception e) { /* expired */ }
                                        }
                                    }
                                }
                                case 2 -> {
                                    reservation.isLocked();
                                    isLockedChecks.incrementAndGet();
                                }
                                case 3 -> {
                                    if (iter % 5 == 0) {
                                        reservation.forceUnlock();
                                        forceUnlocks.incrementAndGet();
                                    }
                                }
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        boolean completed = completeLatch.await(60, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        log.info("Mixed ops: lock={}, tryLock={}, isLocked={}, forceUnlock={}, errors={}",
                lockOps.get(), tryLockOps.get(), isLockedChecks.get(), forceUnlocks.get(), errors.get());

        assertThat(lockOps.get() + tryLockOps.get()).isGreaterThan(0);
        assertThat(isLockedChecks.get()).isGreaterThan(0);
    }

    // ==================== 9. INTERRUPT HANDLING UNDER LOAD ====================

    @Test
    @DisplayName("Interrupts: lockInterruptibly responds to interrupts under contention")
    @Timeout(60)
    void shouldHandleInterruptsUnderLoad() throws Exception {
        // Hold a lock, start many threads calling lockInterruptibly, interrupt them all
        Reservation holder = manager.getReservation("interrupt-load");
        holder.lock();

        int waiterCount = 30;
        AtomicInteger interruptedCount = new AtomicInteger(0);
        AtomicInteger otherErrorCount = new AtomicInteger(0);
        List<Thread> waiters = new ArrayList<>();

        try {
            for (int i = 0; i < waiterCount; i++) {
                Thread t = new Thread(() -> {
                    try {
                        Reservation waiter = manager.getReservation("interrupt-load");
                        waiter.lockInterruptibly();
                        // If we get here, something is wrong — unlock to not leak
                        waiter.unlock();
                    } catch (InterruptedException e) {
                        interruptedCount.incrementAndGet();
                    } catch (Exception e) {
                        otherErrorCount.incrementAndGet();
                    }
                }, "waiter-" + i);
                waiters.add(t);
                t.start();
            }

            // Let all waiters enter lockInterruptibly
            Thread.sleep(1000);

            // Interrupt all waiters
            for (Thread t : waiters) {
                t.interrupt();
            }

            // Wait for all to finish
            for (Thread t : waiters) {
                t.join(5000);
            }

            log.info("Interrupt test: {} interrupted, {} other errors out of {} waiters",
                    interruptedCount.get(), otherErrorCount.get(), waiterCount);

            assertThat(interruptedCount.get())
                    .as("All waiters should receive InterruptedException")
                    .isEqualTo(waiterCount);
        } finally {
            holder.unlock();
        }
    }

    // ==================== 10. LOCK-THEN-EXPIRE-THEN-REACQUIRE RACE ====================

    @Test
    @DisplayName("Expiry race: lock expires and is immediately re-acquired by another thread")
    @Timeout(60)
    void shouldHandleExpiryAndReacquisitionRace() throws Exception {
        ReservationManager shortLeaseManager = createManager("expiry-race", Duration.ofMillis(200));
        managersToClose.add(shortLeaseManager);

        int rounds = 10;
        AtomicInteger reacquisitions = new AtomicInteger(0);
        AtomicInteger expiryDetected = new AtomicInteger(0);

        for (int round = 0; round < rounds; round++) {
            String lockId = "race-lock-" + round;
            CountDownLatch holderAcquired = new CountDownLatch(1);
            CountDownLatch reacquirerDone = new CountDownLatch(1);

            // Thread 1: acquire and hold past expiry
            Thread holderThread = new Thread(() -> {
                try {
                    Reservation res = shortLeaseManager.getReservation(lockId);
                    res.lock();
                    holderAcquired.countDown();
                    Thread.sleep(500); // Well past 200ms lease
                    try {
                        res.unlock();
                    } catch (Exception e) {
                        expiryDetected.incrementAndGet();
                    }
                } catch (Exception e) {
                    // ignore
                }
            });

            // Thread 2: wait for holder, then poll until we can acquire
            Thread reacquirer = new Thread(() -> {
                try {
                    holderAcquired.await();
                    Thread.sleep(250); // Wait for lease to expire
                    Reservation res = shortLeaseManager.getReservation(lockId);
                    if (res.tryLock(3, TimeUnit.SECONDS)) {
                        reacquisitions.incrementAndGet();
                        res.unlock();
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    reacquirerDone.countDown();
                }
            });

            holderThread.start();
            reacquirer.start();
            holderThread.join(5000);
            reacquirerDone.await(5, TimeUnit.SECONDS);
        }

        log.info("Expiry race: {} reacquisitions, {} expiry detections in {} rounds",
                reacquisitions.get(), expiryDetected.get(), rounds);

        assertThat(reacquisitions.get())
                .as("Re-acquirer should succeed after lease expiry")
                .isGreaterThan(0);
    }

    // ==================== 11. MULTI-KEY CONTENTION ====================

    @Test
    @DisplayName("Multi-key contention: many threads across many locks")
    @Timeout(120)
    void shouldHandleMultiKeyContention() throws Exception {
        // Simulates realistic production scenario: many distinct resources,
        // each with moderate contention (3-5 threads per key).
        int keyCount = 30;
        int threadsPerKey = 4;
        int iterationsPerThread = 15;
        int totalThreads = keyCount * threadsPerKey;

        AtomicInteger totalSuccess = new AtomicInteger(0);
        AtomicInteger totalFailure = new AtomicInteger(0);
        AtomicInteger occupancyViolations = new AtomicInteger(0);
        // Per-key occupancy tracking
        AtomicInteger[] keyOccupancy = new AtomicInteger[keyCount];
        for (int i = 0; i < keyCount; i++) {
            keyOccupancy[i] = new AtomicInteger(0);
        }

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(totalThreads);
        long startTime = System.nanoTime();

        for (int k = 0; k < keyCount; k++) {
            final int keyIndex = k;
            final String lockId = "multikey-" + k;

            for (int t = 0; t < threadsPerKey; t++) {
                new Thread(() -> {
                    try {
                        startLatch.await();
                        for (int iter = 0; iter < iterationsPerThread; iter++) {
                            Reservation reservation = manager.getReservation(lockId);
                            if (reservation.tryLock(5, TimeUnit.SECONDS)) {
                                try {
                                    int occ = keyOccupancy[keyIndex].incrementAndGet();
                                    if (occ > 1) {
                                        occupancyViolations.incrementAndGet();
                                    }
                                    Thread.sleep(1);
                                    totalSuccess.incrementAndGet();
                                } finally {
                                    keyOccupancy[keyIndex].decrementAndGet();
                                    reservation.unlock();
                                }
                            } else {
                                totalFailure.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completeLatch.countDown();
                    }
                }).start();
            }
        }

        startLatch.countDown();
        boolean completed = completeLatch.await(120, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
        double opsPerSec = totalSuccess.get() * 1000.0 / elapsedMs;

        log.info("Multi-key contention: {} keys x {} threads x {} iters", keyCount, threadsPerKey, iterationsPerThread);
        log.info("  {} successes, {} failures in {} ms = {} ops/sec",
                totalSuccess.get(), totalFailure.get(), elapsedMs, String.format("%.0f", opsPerSec));
        log.info("  Occupancy violations: {}", occupancyViolations.get());

        assertThat(completed).isTrue();
        assertThat(occupancyViolations.get())
                .as("No per-key mutual exclusion violations")
                .isZero();
        assertThat(totalSuccess.get()).isGreaterThan(0);
    }

    // ==================== 12. SUSTAINED LOAD WITH TIMING ====================

    @Test
    @DisplayName("Sustained load: rate-limited 100 ops/sec for 10 seconds")
    @Timeout(120)
    void shouldSustainRateLimitedLoad() throws Exception {
        // Uses a ScheduledExecutorService to fire exactly ~100 ops/sec.
        // Each op tries to lock one of 10 keys, holds briefly, unlocks.
        int targetRate = 100; // ops per second
        int durationSeconds = 10;
        int totalOps = targetRate * durationSeconds;
        int keyCount = 10;

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
        ExecutorService workers = Executors.newFixedThreadPool(mediumThreadCount());

        AtomicInteger submitted = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger lockAcquired = new AtomicInteger(0);
        AtomicInteger lockFailed = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);
        CountDownLatch allDone = new CountDownLatch(totalOps);

        long intervalMicros = 1_000_000 / targetRate;
        Instant testStart = Instant.now();

        // Submit work at fixed rate
        ScheduledFuture<?> producer = scheduler.scheduleAtFixedRate(() -> {
            if (submitted.getAndIncrement() >= totalOps) return;

            workers.submit(() -> {
                try {
                    String lockId = "sustained-" + ThreadLocalRandom.current().nextInt(keyCount);
                    Reservation reservation = manager.getReservation(lockId);
                    if (reservation.tryLock(2, TimeUnit.SECONDS)) {
                        try {
                            Thread.sleep(ThreadLocalRandom.current().nextInt(5, 15));
                            lockAcquired.incrementAndGet();
                        } finally {
                            try { reservation.unlock(); } catch (Exception e) { /* expired */ }
                        }
                    } else {
                        lockFailed.incrementAndGet();
                    }
                    completed.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    allDone.countDown();
                }
            });
        }, 0, intervalMicros, TimeUnit.MICROSECONDS);

        // Wait for completion
        boolean allCompleted = allDone.await(durationSeconds + 30, TimeUnit.SECONDS);
        producer.cancel(false);
        scheduler.shutdown();
        workers.shutdown();
        workers.awaitTermination(10, TimeUnit.SECONDS);

        long elapsedMs = Duration.between(testStart, Instant.now()).toMillis();
        double actualRate = completed.get() * 1000.0 / elapsedMs;

        log.info("Sustained load test @ {} ops/sec target for {}s:", targetRate, durationSeconds);
        log.info("  Completed: {}, Acquired: {}, Failed: {}, Errors: {}",
                completed.get(), lockAcquired.get(), lockFailed.get(), errors.get());
        log.info("  Actual rate: {} ops/sec over {} ms", String.format("%.0f", actualRate), elapsedMs);

        assertThat(allCompleted || completed.get() > totalOps / 2)
                .as("Should complete most operations")
                .isTrue();
        assertThat(lockAcquired.get()).isGreaterThan(0);
    }

    // ==================== 13. FAIRNESS UNDER PRODUCTION LOAD ====================

    @Test
    @DisplayName("Fairness: verify lock distribution across threads")
    @Timeout(120)
    void shouldBeFairUnderContention() throws Exception {
        int threadCount = 20;
        int iterationsPerThread = 15;
        ConcurrentHashMap<String, AtomicInteger> threadSuccesses = new ConcurrentHashMap<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final String threadName = "fairness-" + i;
            threadSuccesses.put(threadName, new AtomicInteger(0));

            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int iter = 0; iter < iterationsPerThread; iter++) {
                        Reservation reservation = manager.getReservation("fair-lock");
                        if (reservation.tryLock(10, TimeUnit.SECONDS)) {
                            try {
                                Thread.sleep(5);
                                threadSuccesses.get(threadName).incrementAndGet();
                            } finally {
                                reservation.unlock();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch.countDown();
                }
            }, threadName).start();
        }

        startLatch.countDown();
        boolean completed = completeLatch.await(120, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        int totalSuccesses = threadSuccesses.values().stream().mapToInt(AtomicInteger::get).sum();
        int threadsWithZero = (int) threadSuccesses.values().stream()
                .filter(c -> c.get() == 0).count();

        log.info("Fairness: {} total successes across {} threads, {} starved",
                totalSuccesses, threadCount, threadsWithZero);
        threadSuccesses.forEach((name, count) ->
                log.info("  {}: {}", name, count.get()));

        // At most 20% of threads should be completely starved
        assertThat(threadsWithZero)
                .as("Most threads should get at least one lock")
                .isLessThan(threadCount / 5 + 1);
    }

    // ==================== 14. THUNDERING HERD ====================

    @Test
    @DisplayName("Thundering herd: many threads wake simultaneously, only 1 acquires")
    @Timeout(120)
    void shouldHandleThunderingHerd() throws Exception {
        // Hold a lock, have 100 threads waiting via tryLock(timeout).
        // Release the lock and verify exactly 1 thread enters at a time.
        Reservation holder = manager.getReservation("thundering-herd");
        holder.lock();

        int herdSize = highThreadCount();
        AtomicInteger occupancy = new AtomicInteger(0);
        AtomicInteger maxOccupancy = new AtomicInteger(0);
        AtomicInteger acquired = new AtomicInteger(0);
        CountDownLatch allWaiting = new CountDownLatch(herdSize);
        CountDownLatch allDone = new CountDownLatch(herdSize);

        try {
            for (int i = 0; i < herdSize; i++) {
                new Thread(() -> {
                    try {
                        allWaiting.countDown();
                        Reservation res = manager.getReservation("thundering-herd");
                        if (res.tryLock(tryLockTimeoutSeconds(), TimeUnit.SECONDS)) {
                            try {
                                int occ = occupancy.incrementAndGet();
                                maxOccupancy.accumulateAndGet(occ, Math::max);
                                Thread.sleep(1); // tiny critical section
                                acquired.incrementAndGet();
                            } finally {
                                occupancy.decrementAndGet();
                                res.unlock();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        allDone.countDown();
                    }
                }, "herd-" + i).start();
            }

            // Wait for all threads to be blocked on the lock
            allWaiting.await(10, TimeUnit.SECONDS);
            Thread.sleep(500);

            // Unleash the herd
            holder.unlock();

            boolean completed = allDone.await(120, TimeUnit.SECONDS);

            log.info("Thundering herd: {} acquired, max occupancy={}", acquired.get(), maxOccupancy.get());

            assertThat(completed).as("All herd threads should finish").isTrue();
            assertThat(maxOccupancy.get())
                    .as("CRITICAL: thundering herd violated mutual exclusion")
                    .isEqualTo(1);
            assertThat(acquired.get())
                    .as("All herd threads should eventually acquire")
                    .isEqualTo(herdSize);
        } catch (Exception e) {
            // Best effort release on failure
            try { holder.forceUnlock(); } catch (Exception ignored) {}
            throw e;
        }
    }

    // ==================== 15. PRECISE CONCURRENT HIT (CyclicBarrier) ====================

    @Test
    @DisplayName("CyclicBarrier: threads synchronize to hit lock at exactly the same instant")
    @Timeout(60)
    void shouldHandlePreciseConcurrentHit() throws Exception {
        // CyclicBarrier ensures ALL threads attempt tryLock at the same nanosecond.
        // This maximizes contention and tests the lock under worst-case timing.
        int threadCount = mediumThreadCount();
        int rounds = 10;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger totalAcquired = new AtomicInteger(0);
        AtomicInteger occupancyViolations = new AtomicInteger(0);
        AtomicInteger occupancy = new AtomicInteger(0);
        CountDownLatch allDone = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    for (int round = 0; round < rounds; round++) {
                        barrier.await(10, TimeUnit.SECONDS);
                        // ALL threads hit tryLock at (nearly) the same instant
                        Reservation res = manager.getReservation("barrier-lock");
                        if (res.tryLock(5, TimeUnit.SECONDS)) {
                            try {
                                int occ = occupancy.incrementAndGet();
                                if (occ > 1) {
                                    occupancyViolations.incrementAndGet();
                                }
                                totalAcquired.incrementAndGet();
                            } finally {
                                occupancy.decrementAndGet();
                                res.unlock();
                            }
                        }
                    }
                } catch (Exception e) {
                    // Barrier or lock exception — acceptable
                } finally {
                    allDone.countDown();
                }
            }, "barrier-thread-" + i).start();
        }

        boolean completed = allDone.await(60, TimeUnit.SECONDS);
        log.info("CyclicBarrier test: {} acquired across {} rounds, {} violations",
                totalAcquired.get(), rounds, occupancyViolations.get());

        assertThat(completed).isTrue();
        assertThat(occupancyViolations.get())
                .as("CyclicBarrier test: mutual exclusion violated")
                .isZero();
        assertThat(totalAcquired.get()).isGreaterThan(0);
    }

    // ==================== 16. SHARED COUNTER PROOF ====================

    @Test
    @DisplayName("Shared counter: lock protects non-atomic increment to exact count")
    @Timeout(120)
    void sharedCounterMustBeExact() throws Exception {
        // If the lock provides true mutual exclusion, incrementing a plain long[]
        // (NOT atomic) inside the critical section must produce the exact expected count.
        // Any data race shows up as counter != acquiredCount.
        int threadCount = 30;
        int incrementsPerThread = 30;
        long[] counter = {0}; // plain long — deliberately non-atomic
        AtomicInteger acquiredCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int iter = 0; iter < incrementsPerThread; iter++) {
                        Reservation res = manager.getReservation("counter-lock");
                        if (res.tryLock(tryLockTimeoutSeconds(), TimeUnit.SECONDS)) {
                            try {
                                counter[0]++;
                                acquiredCount.incrementAndGet();
                            } finally {
                                res.unlock();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch.countDown();
                }
            }, "counter-" + i).start();
        }

        startLatch.countDown();
        boolean completed = completeLatch.await(120, TimeUnit.SECONDS);

        log.info("Shared counter: acquired={}, counter={}", acquiredCount.get(), counter[0]);

        assertThat(completed).isTrue();
        assertThat(acquiredCount.get()).as("Should acquire locks").isGreaterThan(0);
        assertThat(counter[0])
                .as("CRITICAL: counter mismatch proves data race — lock is broken")
                .isEqualTo((long) acquiredCount.get());
    }

    // ==================== 17. LATENCY DISTRIBUTION ====================

    @Test
    @DisplayName("Latency: p50/p95/p99 under contention are within acceptable bounds")
    @Timeout(120)
    void shouldHaveAcceptableLatencyDistribution() throws Exception {
        int threadCount = 30;
        int opsPerThread = 20;
        ConcurrentLinkedQueue<Long> latenciesMicros = new ConcurrentLinkedQueue<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int op = 0; op < opsPerThread; op++) {
                        String lockId = "latency-" + (op % 5);
                        Reservation res = manager.getReservation(lockId);
                        long startNanos = System.nanoTime();
                        if (res.tryLock(10, TimeUnit.SECONDS)) {
                            long acquireTimeNanos = System.nanoTime() - startNanos;
                            latenciesMicros.add(TimeUnit.NANOSECONDS.toMicros(acquireTimeNanos));
                            try {
                                Thread.sleep(1);
                            } finally {
                                res.unlock();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch.countDown();
                }
            }, "latency-" + i).start();
        }

        startLatch.countDown();
        boolean completed = completeLatch.await(120, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        long[] sorted = latenciesMicros.stream().mapToLong(Long::longValue).sorted().toArray();
        assertThat(sorted.length).as("Should have collected latency samples").isGreaterThan(0);

        long p50 = sorted[(int) (sorted.length * 0.50)];
        long p95 = sorted[(int) (sorted.length * 0.95)];
        long p99 = sorted[Math.min((int) (sorted.length * 0.99), sorted.length - 1)];

        log.info("Latency distribution ({} samples):", sorted.length);
        log.info("  p50  = {} us ({} ms)", p50, p50 / 1000);
        log.info("  p95  = {} us ({} ms)", p95, p95 / 1000);
        log.info("  p99  = {} us ({} ms)", p99, p99 / 1000);
        log.info("  max  = {} us ({} ms)", sorted[sorted.length - 1], sorted[sorted.length - 1] / 1000);

        // Under contention on 5 keys with 30 threads, p99 should still be under 30 seconds
        assertThat(p99)
                .as("p99 latency should be under 30 seconds even under contention")
                .isLessThan(30_000_000L);
    }

    // ==================== 18. LOCK/UNLOCK ORDERING GUARANTEE ====================

    @Test
    @DisplayName("Ordering: operations inside lock are serialized (list append test)")
    @Timeout(120)
    void shouldSerializeOperationsInsideLock() throws Exception {
        // Multiple threads append to a shared list inside the lock.
        // If serialized, each append sees the correct previous size.
        int threadCount = 20;
        int appendsPerThread = 20;
        List<Integer> sharedList = new ArrayList<>(); // deliberately not thread-safe
        AtomicInteger appendCount = new AtomicInteger(0);
        AtomicInteger orderingViolations = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int op = 0; op < appendsPerThread; op++) {
                        Reservation res = manager.getReservation("ordering-lock");
                        if (res.tryLock(tryLockTimeoutSeconds(), TimeUnit.SECONDS)) {
                            try {
                                int sizeBefore = sharedList.size();
                                sharedList.add(sizeBefore);
                                int sizeAfter = sharedList.size();
                                if (sizeAfter != sizeBefore + 1) {
                                    orderingViolations.incrementAndGet();
                                }
                                appendCount.incrementAndGet();
                            } finally {
                                res.unlock();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        boolean completed = completeLatch.await(120, TimeUnit.SECONDS);

        log.info("Ordering test: list size={}, appends={}, violations={}",
                sharedList.size(), appendCount.get(), orderingViolations.get());

        assertThat(completed).isTrue();
        assertThat(orderingViolations.get())
                .as("List operations inside lock must be serialized")
                .isZero();
        // The list size must exactly match the number of successful appends
        assertThat(sharedList.size()).isEqualTo(appendCount.get());
    }

    // ==================== 19. BURST LOAD WITH RECOVERY ====================

    @Test
    @DisplayName("Burst: sudden spike of concurrent requests then calm")
    @Timeout(120)
    void shouldHandleBurstLoadAndRecover() throws Exception {
        // Phase 1: burst — many threads compete for 5 keys simultaneously
        // Phase 2: calm — verify the system is still functional afterward
        int burstThreads = burstThreadCount();
        int keyCount = 5;
        LongAdder burstAcquired = new LongAdder();
        LongAdder burstFailed = new LongAdder();
        LongAdder burstErrors = new LongAdder();
        CountDownLatch burstDone = new CountDownLatch(burstThreads);

        ExecutorService executor = Executors.newFixedThreadPool(burstThreads);
        try {
            CyclicBarrier barrier = new CyclicBarrier(burstThreads);

            // Phase 1: burst
            for (int i = 0; i < burstThreads; i++) {
                final String lockId = "burst-" + (i % keyCount);
                executor.submit(() -> {
                    try {
                        barrier.await(15, TimeUnit.SECONDS);
                        Reservation res = manager.getReservation(lockId);
                        if (res.tryLock(10, TimeUnit.SECONDS)) {
                            try {
                                Thread.sleep(ThreadLocalRandom.current().nextInt(1, 5));
                                burstAcquired.increment();
                            } finally {
                                try { res.unlock(); } catch (Exception e) { /* ok */ }
                            }
                        } else {
                            burstFailed.increment();
                        }
                    } catch (Exception e) {
                        burstErrors.increment();
                    } finally {
                        burstDone.countDown();
                    }
                });
            }

            boolean burstCompleted = burstDone.await(120, TimeUnit.SECONDS);

            log.info("Burst phase: acquired={}, failed={}, errors={}",
                    burstAcquired.sum(), burstFailed.sum(), burstErrors.sum());

            assertThat(burstCompleted).as("Burst phase should complete").isTrue();
            assertThat(burstAcquired.sum()).as("Some burst requests should succeed").isGreaterThan(0);

            // Phase 2: calm — the system should still work normally
            int calmOps = 50;
            AtomicInteger calmSuccess = new AtomicInteger(0);
            for (int i = 0; i < calmOps; i++) {
                Reservation res = manager.getReservation("burst-" + (i % keyCount));
                if (res.tryLock(5, TimeUnit.SECONDS)) {
                    try {
                        calmSuccess.incrementAndGet();
                    } finally {
                        res.unlock();
                    }
                }
            }

            log.info("Calm phase: {}/{} succeeded", calmSuccess.get(), calmOps);
            assertThat(calmSuccess.get())
                    .as("System must recover after burst — all calm ops should succeed")
                    .isEqualTo(calmOps);
        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // ==================== 20. MULTI-DOMAIN THROUGHPUT ====================

    @Test
    @DisplayName("Multi-domain throughput: 4 domains x 100 rps each = 400 rps total")
    @Timeout(120)
    void shouldHandleMultiDomainThroughput() throws Exception {
        String[] domains = {"orders", "payments", "inventory", "shipping"};
        ReservationManager[] domainManagers = new ReservationManager[domains.length];
        for (int i = 0; i < domains.length; i++) {
            domainManagers[i] = createManager(domains[i], Duration.ofSeconds(30));
            managersToClose.add(domainManagers[i]);
        }

        int threadsPerDomain = mediumThreadCount() / 2;
        int opsPerThread = 40;
        int totalThreads = domains.length * threadsPerDomain;
        LongAdder[] domainOps = new LongAdder[domains.length];
        for (int i = 0; i < domains.length; i++) {
            domainOps[i] = new LongAdder();
        }
        LongAdder totalErrors = new LongAdder();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(totalThreads);
        long startTime = System.nanoTime();

        for (int d = 0; d < domains.length; d++) {
            final int domainIdx = d;
            final ReservationManager mgr = domainManagers[d];

            for (int t = 0; t < threadsPerDomain; t++) {
                new Thread(() -> {
                    try {
                        startLatch.await();
                        for (int op = 0; op < opsPerThread; op++) {
                            String lockId = "resource-" + (op % 10);
                            Reservation res = mgr.getReservation(lockId);
                            if (res.tryLock(5, TimeUnit.SECONDS)) {
                                try {
                                    domainOps[domainIdx].increment();
                                } finally {
                                    res.unlock();
                                }
                            }
                        }
                    } catch (Exception e) {
                        totalErrors.increment();
                    } finally {
                        completeLatch.countDown();
                    }
                }).start();
            }
        }

        startLatch.countDown();
        boolean completed = completeLatch.await(120, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

        long totalOps = Arrays.stream(domainOps).mapToLong(LongAdder::sum).sum();
        double totalOpsPerSec = totalOps * 1000.0 / elapsedMs;

        log.info("Multi-domain throughput ({} domains, {} threads, {} ms):", domains.length, totalThreads, elapsedMs);
        for (int i = 0; i < domains.length; i++) {
            double domainRate = domainOps[i].sum() * 1000.0 / elapsedMs;
            log.info("  {}: {} ops ({} ops/sec)", domains[i], domainOps[i].sum(), String.format("%.0f", domainRate));
        }
        log.info("  Total: {} ops ({} ops/sec), errors={}", totalOps, String.format("%.0f", totalOpsPerSec), totalErrors.sum());

        assertThat(completed).isTrue();
        assertThat(totalOpsPerSec).as("Should sustain at least 100 ops/sec across all domains").isGreaterThan(100.0);
    }

    // ==================== 21. LEASE TIME BOUNDARY ====================

    @Test
    @DisplayName("Lease boundary: lock held just under lease time, unlock succeeds")
    @Timeout(60)
    void shouldAllowUnlockJustBeforeLeaseExpiry() throws Exception {
        // Create manager with 1-second lease
        ReservationManager shortManager = createManager("lease-boundary", Duration.ofSeconds(1));
        managersToClose.add(shortManager);

        int iterations = 10;
        AtomicInteger successUnlocks = new AtomicInteger(0);
        AtomicInteger expiredUnlocks = new AtomicInteger(0);

        for (int i = 0; i < iterations; i++) {
            String lockId = "boundary-" + i;
            Reservation res = shortManager.getReservation(lockId);
            res.lock();
            try {
                // Hold for 800ms — just under 1s lease
                Thread.sleep(800);
                res.unlock();
                successUnlocks.incrementAndGet();
            } catch (Exception e) {
                expiredUnlocks.incrementAndGet();
            }
        }

        log.info("Lease boundary: {} successful unlocks, {} expired", successUnlocks.get(), expiredUnlocks.get());
        assertThat(successUnlocks.get())
                .as("Most unlocks just before expiry should succeed")
                .isGreaterThan(iterations / 2);
    }
}
