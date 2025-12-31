package com.github.reservation.oracle;

import com.github.reservation.Reservation;
import com.github.reservation.ReservationManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multithreaded stress tests for JDBC/Oracle backend.
 *
 * <p>Uses H2 in-memory database for fast testing. The same tests can be run
 * against real Oracle using Testcontainers by switching to OracleContainer.</p>
 *
 * <p>These tests focus on:</p>
 * <ul>
 *   <li>High contention scenarios with many threads competing for same lock</li>
 *   <li>Connection pool behavior under stress</li>
 *   <li>Polling mechanism efficiency under load</li>
 *   <li>Reentrant locking under concurrent access</li>
 *   <li>Expiration behavior under load</li>
 *   <li>Domain isolation under concurrent access</li>
 * </ul>
 *
 * <p>Note: To run against real Oracle, add the Testcontainers Oracle dependency
 * and use OracleContainer instead of H2.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcStressIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(JdbcStressIntegrationTest.class);
    private static final String TABLE_NAME = "RESERVATION_LOCKS";

    private HikariDataSource dataSource;

    @BeforeAll
    void setupDatabase() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:stresstest;DB_CLOSE_DELAY=-1");
        config.setMaximumPoolSize(20); // Higher pool size for stress tests
        config.setMinimumIdle(5);
        config.setConnectionTimeout(5000);
        config.setIdleTimeout(30000);

        dataSource = new HikariDataSource(config);

        // Create table
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS RESERVATION_LOCKS (
                    reservation_key  VARCHAR(512)  NOT NULL,
                    holder           VARCHAR(256)  NOT NULL,
                    acquired_at      TIMESTAMP     NOT NULL,
                    expires_at       TIMESTAMP     NOT NULL,
                    PRIMARY KEY (reservation_key)
                )
                """);
        }

        log.info("Database initialized with {} max connections", 20);
    }

    @AfterAll
    void teardownDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void cleanTable() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM " + TABLE_NAME);
        }
    }

    @Test
    @DisplayName("High contention: 20 threads competing for single lock")
    void shouldHandleHighContention() throws Exception {
        ReservationManager manager = ReservationManager.oracle(dataSource)
                .domain("stress-test")
                .leaseTime(Duration.ofSeconds(30))
                .tableName(TABLE_NAME)
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

        // Should have reasonable success rate
        assertThat(successCount.get()).isGreaterThan(0);

        manager.close();
    }

    @Test
    @DisplayName("Connection pool stress: many concurrent database operations")
    void shouldHandleConnectionPoolStress() throws Exception {
        ReservationManager manager = ReservationManager.oracle(dataSource)
                .domain("pool-stress")
                .leaseTime(Duration.ofSeconds(30))
                .tableName(TABLE_NAME)
                .build();

        int threadCount = 30; // More threads than pool size
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger connectionErrors = new AtomicInteger(0);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final String lockId = "pool-lock-" + (i % 10); // 10 unique locks shared among 30 threads

            new Thread(() -> {
                try {
                    for (int iter = 0; iter < 10; iter++) {
                        Reservation reservation = manager.getReservation(lockId);

                        if (reservation.tryLock(3, TimeUnit.SECONDS)) {
                            try {
                                Thread.sleep(10);
                                successCount.incrementAndGet();
                            } finally {
                                try {
                                    reservation.unlock();
                                } catch (Exception e) {
                                    // May expire
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("Connection")) {
                        connectionErrors.incrementAndGet();
                    }
                } finally {
                    completeLatch.countDown();
                }
            }).start();
        }

        boolean completed = completeLatch.await(120, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        log.info("Connection pool stress test:");
        log.info("  Threads: {}, Pool size: {}", threadCount, 20);
        log.info("  Successful operations: {}, Connection errors: {}",
                successCount.get(), connectionErrors.get());

        assertThat(connectionErrors.get()).isZero()
                .as("Should handle connection pool contention gracefully");

        manager.close();
    }

    @Test
    @DisplayName("Polling mechanism efficiency under load")
    void shouldPollEfficientlyUnderLoad() throws Exception {
        ReservationManager manager = ReservationManager.oracle(dataSource)
                .domain("polling-test")
                .leaseTime(Duration.ofSeconds(30))
                .tableName(TABLE_NAME)
                .build();

        // First, acquire the lock
        Reservation holder = manager.getReservation("polling-lock");
        holder.lock();

        // Start multiple threads that will poll for the lock
        int pollerCount = 5;
        AtomicLong totalPollWaitTime = new AtomicLong(0);
        AtomicInteger successfulAcquisitions = new AtomicInteger(0);
        CountDownLatch pollersStarted = new CountDownLatch(pollerCount);
        CountDownLatch pollersDone = new CountDownLatch(pollerCount);

        for (int i = 0; i < pollerCount; i++) {
            new Thread(() -> {
                try {
                    pollersStarted.countDown();
                    Reservation reservation = manager.getReservation("polling-lock");
                    long startTime = System.currentTimeMillis();

                    if (reservation.tryLock(10, TimeUnit.SECONDS)) {
                        try {
                            long waitTime = System.currentTimeMillis() - startTime;
                            totalPollWaitTime.addAndGet(waitTime);
                            successfulAcquisitions.incrementAndGet();
                        } finally {
                            reservation.unlock();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    pollersDone.countDown();
                }
            }).start();
        }

        // Wait for pollers to start, then release the lock after a delay
        pollersStarted.await();
        Thread.sleep(500); // Let pollers start polling
        holder.unlock();

        boolean completed = pollersDone.await(30, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        log.info("Polling efficiency test:");
        log.info("  Pollers: {}, Successful acquisitions: {}",
                pollerCount, successfulAcquisitions.get());
        if (successfulAcquisitions.get() > 0) {
            log.info("  Average wait time: {} ms",
                    totalPollWaitTime.get() / successfulAcquisitions.get());
        }

        // At least some pollers should succeed
        assertThat(successfulAcquisitions.get()).isGreaterThan(0);

        manager.close();
    }

    @Test
    @DisplayName("Reentrant locking under concurrent access")
    void shouldHandleReentrantLockingUnderConcurrency() throws Exception {
        ReservationManager manager = ReservationManager.oracle(dataSource)
                .domain("reentrant-stress")
                .leaseTime(Duration.ofSeconds(30))
                .tableName(TABLE_NAME)
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
            managers[i] = ReservationManager.oracle(dataSource)
                    .domain(domains[i])
                    .leaseTime(Duration.ofSeconds(30))
                    .tableName(TABLE_NAME)
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
        ReservationManager manager = ReservationManager.oracle(dataSource)
                .domain("expiration-stress")
                .leaseTime(Duration.ofMillis(500))
                .tableName(TABLE_NAME)
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

        // Some expirations should have occurred
        assertThat(expiredBeforeUnlock.get()).isGreaterThan(0);

        manager.close();
    }

    @Test
    @DisplayName("Rapid lock/unlock cycles")
    void shouldHandleRapidLockUnlockCycles() throws Exception {
        ReservationManager manager = ReservationManager.oracle(dataSource)
                .domain("rapid-cycles")
                .leaseTime(Duration.ofSeconds(30))
                .tableName(TABLE_NAME)
                .build();

        int threadCount = 5;
        int cyclesPerThread = 30; // Fewer cycles due to DB overhead
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

    @Test
    @DisplayName("Mixed operations under load")
    void shouldHandleMixedOperationsUnderLoad() throws Exception {
        ReservationManager manager = ReservationManager.oracle(dataSource)
                .domain("mixed-ops")
                .leaseTime(Duration.ofSeconds(10))
                .tableName(TABLE_NAME)
                .build();

        int threadCount = 15;
        AtomicInteger lockOps = new AtomicInteger(0);
        AtomicInteger tryLockOps = new AtomicInteger(0);
        AtomicInteger isLockedChecks = new AtomicInteger(0);
        AtomicInteger forceUnlocks = new AtomicInteger(0);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadType = i % 4; // Different operation types
            final String lockId = "mixed-" + (i % 3); // 3 shared locks

            new Thread(() -> {
                try {
                    for (int iter = 0; iter < 10; iter++) {
                        Reservation reservation = manager.getReservation(lockId);

                        switch (threadType) {
                            case 0 -> {
                                // Lock/unlock pattern
                                if (reservation.tryLock(1, TimeUnit.SECONDS)) {
                                    try {
                                        Thread.sleep(10);
                                        lockOps.incrementAndGet();
                                    } finally {
                                        try {
                                            reservation.unlock();
                                        } catch (Exception e) {
                                            // May have expired
                                        }
                                    }
                                }
                            }
                            case 1 -> {
                                // Quick tryLock pattern
                                if (reservation.tryLock()) {
                                    try {
                                        tryLockOps.incrementAndGet();
                                    } finally {
                                        try {
                                            reservation.unlock();
                                        } catch (Exception e) {
                                            // May have expired
                                        }
                                    }
                                }
                            }
                            case 2 -> {
                                // isLocked check pattern
                                reservation.isLocked();
                                isLockedChecks.incrementAndGet();
                            }
                            case 3 -> {
                                // Occasional force unlock (admin pattern)
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

        // All operation counts should be positive
        assertThat(lockOps.get() + tryLockOps.get()).isGreaterThan(0);
        assertThat(isLockedChecks.get()).isGreaterThan(0);

        manager.close();
    }
}
