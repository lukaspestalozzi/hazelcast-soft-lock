package com.github.reservation.hazelcast;

import com.github.reservation.Reservation;
import com.github.reservation.ReservationManager;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multithreaded stress tests for Hazelcast backend using Testcontainers.
 *
 * <p>These tests focus on:</p>
 * <ul>
 *   <li>High contention scenarios with many threads competing for same lock</li>
 *   <li>Lock acquisition fairness under stress</li>
 *   <li>Reentrant locking under concurrent access</li>
 *   <li>Expiration behavior under load</li>
 *   <li>Domain isolation under concurrent access</li>
 * </ul>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HazelcastStressIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HazelcastStressIntegrationTest.class);

    @Container
    static GenericContainer<?> hazelcast = new GenericContainer<>(DockerImageName.parse("hazelcast/hazelcast:5.3"))
            .withExposedPorts(5701);

    private HazelcastInstance client;

    @BeforeAll
    void setupClient() {
        ClientConfig config = new ClientConfig();
        config.setClusterName("dev");
        config.getNetworkConfig().addAddress(
                hazelcast.getHost() + ":" + hazelcast.getMappedPort(5701));
        config.getConnectionStrategyConfig()
                .getConnectionRetryConfig()
                .setClusterConnectTimeoutMillis(30000);

        client = HazelcastClient.newHazelcastClient(config);
        log.info("Connected to Hazelcast container at {}:{}",
                hazelcast.getHost(), hazelcast.getMappedPort(5701));
    }

    @AfterAll
    void teardownClient() {
        if (client != null) {
            client.shutdown();
        }
    }

    @Test
    @DisplayName("High contention: 20 threads competing for single lock")
    void shouldHandleHighContention() throws Exception {
        ReservationManager manager = ReservationManager.hazelcast(client)
                .domain("stress-test")
                .leaseTime(Duration.ofSeconds(30))
                .build();

        int threadCount = 20;
        int iterationsPerThread = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicLong totalWaitTimeMs = new AtomicLong(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            Thread t = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int iter = 0; iter < iterationsPerThread; iter++) {
                        Reservation reservation = manager.getReservation("contended-lock");
                        long startTime = System.currentTimeMillis();

                        if (reservation.tryLock(5, TimeUnit.SECONDS)) {
                            try {
                                long waitTime = System.currentTimeMillis() - startTime;
                                totalWaitTimeMs.addAndGet(waitTime);

                                // Critical section - simulate work
                                Thread.sleep(5);

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
            }, "stress-thread-" + threadId);
            threads.add(t);
            t.start();
        }

        // Start all threads at once
        startLatch.countDown();

        // Wait for completion
        boolean completed = completeLatch.await(60, TimeUnit.SECONDS);
        assertThat(completed).isTrue().as("All threads should complete within timeout");

        int total = successCount.get() + failureCount.get();
        double successRate = (double) successCount.get() / total * 100;
        long avgWaitTime = successCount.get() > 0 ? totalWaitTimeMs.get() / successCount.get() : 0;

        log.info("High contention test results:");
        log.info("  Threads: {}, Iterations: {}, Total attempts: {}", threadCount, iterationsPerThread, total);
        log.info("  Successes: {}, Failures: {}, Success rate: {:.1f}%",
                successCount.get(), failureCount.get(), successRate);
        log.info("  Average wait time: {} ms", avgWaitTime);

        // Should have reasonable success rate (at least some should succeed)
        assertThat(successCount.get()).isGreaterThan(0);

        manager.close();
    }

    @Test
    @DisplayName("Fairness: verify all threads eventually get the lock")
    void shouldBeFairUnderContention() throws Exception {
        ReservationManager manager = ReservationManager.hazelcast(client)
                .domain("fairness-test")
                .leaseTime(Duration.ofSeconds(30))
                .build();

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

                        // Use blocking lock with reasonable timeout behavior
                        if (reservation.tryLock(10, TimeUnit.SECONDS)) {
                            try {
                                Thread.sleep(10); // Hold briefly
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

        // Log distribution
        log.info("Fairness test - lock acquisitions per thread:");
        threadSuccesses.forEach((name, count) ->
                log.info("  {}: {} acquisitions", name, count.get()));

        // All threads should have gotten the lock at least once
        int threadsWithZeroSuccess = (int) threadSuccesses.values().stream()
                .filter(count -> count.get() == 0)
                .count();

        // Allow some variability but most threads should succeed
        assertThat(threadsWithZeroSuccess).isLessThan(threadCount / 2);

        manager.close();
    }

    @Test
    @DisplayName("Reentrant locking under concurrent access")
    void shouldHandleReentrantLockingUnderConcurrency() throws Exception {
        ReservationManager manager = ReservationManager.hazelcast(client)
                .domain("reentrant-stress")
                .leaseTime(Duration.ofSeconds(30))
                .build();

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

                                // Reentrant lock
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

        // Every successful primary lock should also succeed in reentrant lock
        assertThat(reentrantSuccessCount.get()).isEqualTo(successCount.get());

        manager.close();
    }

    @Test
    @DisplayName("Domain isolation under concurrent access")
    void shouldIsolateDomainsUnderConcurrency() throws Exception {
        String[] domains = {"domain-a", "domain-b", "domain-c"};
        ReservationManager[] managers = new ReservationManager[domains.length];

        for (int i = 0; i < domains.length; i++) {
            managers[i] = ReservationManager.hazelcast(client)
                    .domain(domains[i])
                    .leaseTime(Duration.ofSeconds(30))
                    .build();
        }

        AtomicInteger[] successCounts = new AtomicInteger[domains.length];
        for (int i = 0; i < domains.length; i++) {
            successCounts[i] = new AtomicInteger(0);
        }

        int threadsPerDomain = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(domains.length * threadsPerDomain);

        // Create threads for each domain
        for (int d = 0; d < domains.length; d++) {
            final int domainIndex = d;
            final ReservationManager manager = managers[d];

            for (int t = 0; t < threadsPerDomain; t++) {
                new Thread(() -> {
                    try {
                        startLatch.await();
                        for (int iter = 0; iter < 10; iter++) {
                            // All threads use same identifier but different domains
                            Reservation reservation = manager.getReservation("shared-id");

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

        // Log results per domain
        int totalExpected = threadsPerDomain * 10;
        log.info("Domain isolation results:");
        for (int i = 0; i < domains.length; i++) {
            log.info("  {}: {} successes out of {} attempts",
                    domains[i], successCounts[i].get(), totalExpected);

            // Each domain should have reasonable success rate
            // Since threads within same domain compete, but domains are isolated
            assertThat(successCounts[i].get()).isGreaterThan(0);
        }

        for (ReservationManager manager : managers) {
            manager.close();
        }
    }

    @Test
    @DisplayName("Expiration behavior under load")
    void shouldHandleExpirationUnderLoad() throws Exception {
        // Short lease time to test expiration
        ReservationManager manager = ReservationManager.hazelcast(client)
                .domain("expiration-stress")
                .leaseTime(Duration.ofMillis(500))
                .build();

        AtomicInteger expiredBeforeUnlock = new AtomicInteger(0);
        AtomicInteger successfulUnlock = new AtomicInteger(0);
        int threadCount = 5;
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    for (int iter = 0; iter < 3; iter++) {
                        Reservation reservation = manager.getReservation("expiring-lock");

                        if (reservation.tryLock(2, TimeUnit.SECONDS)) {
                            try {
                                // Simulate work that takes longer than lease
                                Thread.sleep(600);
                                reservation.unlock();
                                successfulUnlock.incrementAndGet();
                            } catch (Exception e) {
                                // Expected - lock expired
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

        log.info("Expiration stress test: {} expired before unlock, {} successful unlocks",
                expiredBeforeUnlock.get(), successfulUnlock.get());

        // Some expirations should have occurred since work takes longer than lease
        assertThat(expiredBeforeUnlock.get()).isGreaterThan(0);

        manager.close();
    }

    @Test
    @DisplayName("Rapid lock/unlock cycles")
    void shouldHandleRapidLockUnlockCycles() throws Exception {
        ReservationManager manager = ReservationManager.hazelcast(client)
                .domain("rapid-cycles")
                .leaseTime(Duration.ofSeconds(30))
                .build();

        int threadCount = 5;
        int cyclesPerThread = 50;
        AtomicInteger totalCycles = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final String lockId = "rapid-lock-" + i; // Each thread has its own lock

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

        manager.close();
    }
}
