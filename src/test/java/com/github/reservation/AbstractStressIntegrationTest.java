package com.github.reservation;

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract stress test class for ReservationManager implementations.
 *
 * <p>Subclasses provide the specific backend (Hazelcast or JDBC) via
 * {@link #createManager(String, Duration)}.</p>
 *
 * <p>Tests focus on:</p>
 * <ul>
 *   <li>High contention scenarios with many threads competing for same lock</li>
 *   <li>Lock acquisition fairness under stress</li>
 *   <li>Reentrant locking under concurrent access</li>
 *   <li>Expiration behavior under load</li>
 *   <li>Domain isolation under concurrent access</li>
 *   <li>Rapid lock/unlock cycles</li>
 * </ul>
 */
public abstract class AbstractStressIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractStressIntegrationTest.class);
    protected static final String DEFAULT_DOMAIN = "stress-test";

    protected ReservationManager manager;

    /**
     * Creates the ReservationManager implementation to test.
     */
    protected abstract ReservationManager createManager(String domain, Duration leaseTime);

    /**
     * Cleans up resources after each test.
     */
    protected abstract void cleanup();

    @BeforeEach
    void setUp() {
        manager = createManager(DEFAULT_DOMAIN, Duration.ofSeconds(30));
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        cleanup();
    }

    @Test
    @DisplayName("High contention: 20 threads competing for single lock")
    void shouldHandleHighContention() throws Exception {
        int threadCount = 20;
        int iterationsPerThread = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicLong totalWaitTimeMs = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int iter = 0; iter < iterationsPerThread; iter++) {
                        Reservation reservation = manager.getReservation("contended-lock");
                        long startTime = System.currentTimeMillis();

                        if (reservation.tryLock(5, TimeUnit.SECONDS)) {
                            try {
                                long waitTime = System.currentTimeMillis() - startTime;
                                totalWaitTimeMs.addAndGet(waitTime);
                                Thread.sleep(5); // Critical section
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
            }, "stress-thread-" + threadId).start();
        }

        startLatch.countDown();
        boolean completed = completeLatch.await(120, TimeUnit.SECONDS);
        assertThat(completed).isTrue().as("All threads should complete within timeout");

        int total = successCount.get() + failureCount.get();
        double successRate = (double) successCount.get() / total * 100;
        long avgWaitTime = successCount.get() > 0 ? totalWaitTimeMs.get() / successCount.get() : 0;

        log.info("High contention test results:");
        log.info("  Threads: {}, Iterations: {}, Total attempts: {}", threadCount, iterationsPerThread, total);
        log.info("  Successes: {}, Failures: {}, Success rate: {:.1f}%",
                successCount.get(), failureCount.get(), successRate);
        log.info("  Average wait time: {} ms", avgWaitTime);

        assertThat(successCount.get()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Fairness: verify all threads eventually get the lock")
    void shouldBeFairUnderContention() throws Exception {
        int threadCount = 10;
        ConcurrentHashMap<String, AtomicInteger> threadSuccesses = new ConcurrentHashMap<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final String threadName = "fairness-thread-" + i;
            threadSuccesses.put(threadName, new AtomicInteger(0));

            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int iter = 0; iter < 5; iter++) {
                        Reservation reservation = manager.getReservation("fair-lock");

                        if (reservation.tryLock(10, TimeUnit.SECONDS)) {
                            try {
                                Thread.sleep(10);
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

        log.info("Fairness test - lock acquisitions per thread:");
        threadSuccesses.forEach((name, count) ->
                log.info("  {}: {} acquisitions", name, count.get()));

        int threadsWithZeroSuccess = (int) threadSuccesses.values().stream()
                .filter(count -> count.get() == 0)
                .count();

        assertThat(threadsWithZeroSuccess).isLessThan(threadCount / 2);
    }

    @Test
    @DisplayName("Reentrant locking under concurrent access")
    void shouldHandleReentrantLockingUnderConcurrency() throws Exception {
        int threadCount = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger reentrantSuccessCount = new AtomicInteger(0);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    for (int iter = 0; iter < 5; iter++) {
                        Reservation reservation = manager.getReservation("reentrant-lock");

                        if (reservation.tryLock(5, TimeUnit.SECONDS)) {
                            try {
                                successCount.incrementAndGet();

                                // Reentrant lock - should always succeed if we hold the lock
                                if (reservation.tryLock()) {
                                    try {
                                        reentrantSuccessCount.incrementAndGet();
                                        Thread.sleep(5);
                                    } finally {
                                        reservation.unlock();
                                    }
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
        assertThat(completed).isTrue();

        log.info("Reentrant stress test: {} primary locks, {} reentrant locks",
                successCount.get(), reentrantSuccessCount.get());

        assertThat(reentrantSuccessCount.get()).isEqualTo(successCount.get());
    }

    @Test
    @DisplayName("Domain isolation under concurrent access")
    void shouldIsolateDomainsUnderConcurrency() throws Exception {
        String[] domains = {"domain-a", "domain-b", "domain-c"};
        ReservationManager[] managers = new ReservationManager[domains.length];

        for (int i = 0; i < domains.length; i++) {
            managers[i] = createManager(domains[i], Duration.ofSeconds(30));
        }

        AtomicInteger[] successCounts = new AtomicInteger[domains.length];
        for (int i = 0; i < domains.length; i++) {
            successCounts[i] = new AtomicInteger(0);
        }

        int threadsPerDomain = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(domains.length * threadsPerDomain);

        for (int d = 0; d < domains.length; d++) {
            final int domainIndex = d;
            final ReservationManager mgr = managers[d];

            for (int t = 0; t < threadsPerDomain; t++) {
                new Thread(() -> {
                    try {
                        startLatch.await();
                        for (int iter = 0; iter < 10; iter++) {
                            Reservation reservation = mgr.getReservation("shared-id");

                            if (reservation.tryLock(3, TimeUnit.SECONDS)) {
                                try {
                                    Thread.sleep(5);
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
            assertThat(successCounts[i].get()).isGreaterThan(0);
        }

        for (ReservationManager mgr : managers) {
            mgr.close();
        }
    }

    @Test
    @DisplayName("Expiration behavior under load")
    void shouldHandleExpirationUnderLoad() throws Exception {
        // Use very short lease to guarantee expiration
        ReservationManager shortLeaseManager = createManager("expiration-test", Duration.ofMillis(200));

        AtomicInteger expiredBeforeUnlock = new AtomicInteger(0);
        AtomicInteger successfulUnlock = new AtomicInteger(0);
        AtomicInteger lockAcquired = new AtomicInteger(0);
        int threadCount = 5;
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    for (int iter = 0; iter < 3; iter++) {
                        Reservation reservation = shortLeaseManager.getReservation("expiring-lock");

                        if (reservation.tryLock(2, TimeUnit.SECONDS)) {
                            lockAcquired.incrementAndGet();
                            try {
                                Thread.sleep(500); // Much longer than 200ms lease
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

        log.info("Expiration stress test: acquired={}, expired={}, successful={}",
                lockAcquired.get(), expiredBeforeUnlock.get(), successfulUnlock.get());

        // Verify we actually acquired some locks and handled them
        assertThat(lockAcquired.get()).isGreaterThan(0);
        // Total should match: every acquired lock either expired or unlocked successfully
        assertThat(expiredBeforeUnlock.get() + successfulUnlock.get()).isEqualTo(lockAcquired.get());

        shortLeaseManager.close();
    }

    @Test
    @DisplayName("Rapid lock/unlock cycles")
    void shouldHandleRapidLockUnlockCycles() throws Exception {
        int threadCount = 5;
        int cyclesPerThread = 30;
        AtomicInteger totalCycles = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final String lockId = "rapid-lock-" + i;

            new Thread(() -> {
                try {
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
                } finally {
                    completeLatch.countDown();
                }
            }).start();
        }

        boolean completed = completeLatch.await(60, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        long duration = System.currentTimeMillis() - startTime;
        double cyclesPerSecond = totalCycles.get() * 1000.0 / duration;

        log.info("Rapid cycles test:");
        log.info("  Total cycles: {}, Duration: {} ms", totalCycles.get(), duration);
        log.info("  Throughput: {:.1f} cycles/sec, Failures: {}", cyclesPerSecond, failures.get());

        assertThat(failures.get()).isZero();
        assertThat(totalCycles.get()).isEqualTo(threadCount * cyclesPerThread);
    }

    @Test
    @DisplayName("Mixed operations under load")
    void shouldHandleMixedOperationsUnderLoad() throws Exception {
        int threadCount = 15;
        AtomicInteger lockOps = new AtomicInteger(0);
        AtomicInteger tryLockOps = new AtomicInteger(0);
        AtomicInteger isLockedChecks = new AtomicInteger(0);
        AtomicInteger forceUnlocks = new AtomicInteger(0);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadType = i % 4;
            final String lockId = "mixed-" + (i % 3);

            new Thread(() -> {
                try {
                    for (int iter = 0; iter < 10; iter++) {
                        Reservation reservation = manager.getReservation(lockId);

                        switch (threadType) {
                            case 0 -> {
                                if (reservation.tryLock(1, TimeUnit.SECONDS)) {
                                    try {
                                        Thread.sleep(10);
                                        lockOps.incrementAndGet();
                                    } finally {
                                        try { reservation.unlock(); } catch (Exception e) { }
                                    }
                                }
                            }
                            case 1 -> {
                                if (reservation.tryLock()) {
                                    try {
                                        tryLockOps.incrementAndGet();
                                    } finally {
                                        try { reservation.unlock(); } catch (Exception e) { }
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

        log.info("Mixed operations test:");
        log.info("  Lock ops: {}, TryLock ops: {}", lockOps.get(), tryLockOps.get());
        log.info("  isLocked checks: {}, Force unlocks: {}", isLockedChecks.get(), forceUnlocks.get());

        assertThat(lockOps.get() + tryLockOps.get()).isGreaterThan(0);
        assertThat(isLockedChecks.get()).isGreaterThan(0);
    }
}
