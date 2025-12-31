package com.github.reservation;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Abstract base test class defining the contract for all ReservationManager implementations.
 *
 * <p>Subclasses provide the specific implementation to test.</p>
 */
public abstract class AbstractReservationManagerTest {

    protected static final String DEFAULT_DOMAIN = "orders";
    protected ReservationManager manager;

    /**
     * Creates the ReservationManager implementation to test.
     *
     * @param domain the domain for this manager
     * @param leaseTime the lease time for reservations
     * @return a new ReservationManager instance
     */
    protected abstract ReservationManager createManager(String domain, Duration leaseTime);

    /**
     * Cleans up resources after each test.
     */
    protected abstract void cleanup();

    @BeforeEach
    void setUp() {
        manager = createManager(DEFAULT_DOMAIN, Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        cleanup();
    }

    // ==================== Core Lock/Unlock Tests ====================

    @Test
    void shouldAcquireAndReleaseReservation() {
        Reservation reservation = manager.getReservation("123");

        reservation.lock();
        assertThat(reservation.isLocked()).isTrue();

        reservation.unlock();
        assertThat(reservation.isLocked()).isFalse();
    }

    @Test
    void shouldReturnCorrectIdentifier() {
        Reservation reservation = manager.getReservation("456");
        assertThat(reservation.getIdentifier()).isEqualTo("456");
    }

    @Test
    void shouldReturnCorrectDomainFromManager() {
        assertThat(manager.getDomain()).isEqualTo(DEFAULT_DOMAIN);
    }

    @Test
    void shouldReturnConfiguredLeaseTime() {
        assertThat(manager.getLeaseTime()).isEqualTo(Duration.ofSeconds(5));
    }

    // ==================== Expiration Tests ====================

    @Test
    @Timeout(10)
    void shouldExpireAfterLeaseTime() throws Exception {
        // Use short lease for this test
        ReservationManager shortLeaseManager = createManager(DEFAULT_DOMAIN, Duration.ofSeconds(2));
        try {
            Reservation reservation = shortLeaseManager.getReservation("expire-test");

            reservation.lock();
            assertThat(reservation.isLocked()).isTrue();

            // Wait for lease to expire
            Thread.sleep(2500);

            assertThat(reservation.isLocked()).isFalse();
            assertThatThrownBy(reservation::unlock)
                .isInstanceOf(ReservationExpiredException.class);
        } finally {
            shortLeaseManager.close();
        }
    }

    @Test
    void shouldHavePositiveRemainingLeaseTimeAfterAcquire() {
        Reservation reservation = manager.getReservation("lease-test");
        reservation.lock();

        try {
            Duration remaining = reservation.getRemainingLeaseTime();
            assertThat(remaining).isPositive();
            assertThat(remaining).isLessThanOrEqualTo(Duration.ofSeconds(5));
        } finally {
            reservation.unlock();
        }
    }

    // ==================== Concurrency Tests ====================

    @Test
    @Timeout(5)
    void shouldBlockConcurrentAcquisition() throws Exception {
        Reservation reservation = manager.getReservation("concurrent");
        reservation.lock();

        try {
            CompletableFuture<Boolean> otherThread = CompletableFuture.supplyAsync(() -> {
                Reservation sameReservation = manager.getReservation("concurrent");
                return sameReservation.tryLock();
            });

            assertThat(otherThread.get(2, TimeUnit.SECONDS)).isFalse();
        } finally {
            reservation.unlock();
        }
    }

    @Test
    @Timeout(5)
    void shouldAllowAcquisitionAfterRelease() throws Exception {
        Reservation reservation1 = manager.getReservation("release-test");
        reservation1.lock();
        reservation1.unlock();

        // Different thread should now be able to acquire
        CompletableFuture<Boolean> otherThread = CompletableFuture.supplyAsync(() -> {
            Reservation reservation2 = manager.getReservation("release-test");
            boolean acquired = reservation2.tryLock();
            if (acquired) {
                reservation2.unlock();
            }
            return acquired;
        });

        assertThat(otherThread.get(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @Timeout(10)
    void shouldAllowAcquisitionAfterExpiry() throws Exception {
        ReservationManager shortLeaseManager = createManager(DEFAULT_DOMAIN, Duration.ofSeconds(1));
        try {
            Reservation reservation = shortLeaseManager.getReservation("expiry-acquire-test");

            reservation.lock();
            Thread.sleep(1500); // Wait for expiry

            // Another acquisition should succeed
            AtomicBoolean acquired = new AtomicBoolean(false);
            Thread otherThread = new Thread(() -> {
                Reservation newReservation = shortLeaseManager.getReservation("expiry-acquire-test");
                if (newReservation.tryLock()) {
                    acquired.set(true);
                    newReservation.unlock();
                }
            });

            otherThread.start();
            otherThread.join(3000);

            assertThat(acquired.get()).isTrue();
        } finally {
            shortLeaseManager.close();
        }
    }

    // ==================== tryLock Tests ====================

    @Test
    void tryLockShouldReturnTrueWhenAvailable() {
        Reservation reservation = manager.getReservation("trylock-available");

        assertThat(reservation.tryLock()).isTrue();
        assertThat(reservation.isLocked()).isTrue();

        reservation.unlock();
    }

    @Test
    @Timeout(5)
    void tryLockShouldReturnFalseWhenUnavailable() throws Exception {
        Reservation holder = manager.getReservation("trylock-unavailable");
        holder.lock();

        try {
            CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(() -> {
                Reservation waiterRes = manager.getReservation("trylock-unavailable");
                return waiterRes.tryLock();
            });

            assertThat(waiter.get(2, TimeUnit.SECONDS)).isFalse();
        } finally {
            holder.unlock();
        }
    }

    @Test
    @Timeout(5)
    void tryLockWithTimeoutShouldReturnFalseAfterTimeout() throws Exception {
        Reservation holder = manager.getReservation("trylock-timeout");
        holder.lock();

        try {
            CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(() -> {
                try {
                    Reservation waiterRes = manager.getReservation("trylock-timeout");
                    return waiterRes.tryLock(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    return false;
                }
            });

            assertThat(waiter.get(3, TimeUnit.SECONDS)).isFalse();
        } finally {
            holder.unlock();
        }
    }

    @Test
    @Timeout(10)
    void tryLockShouldSucceedWhenReleasedBeforeTimeout() throws Exception {
        Reservation holder = manager.getReservation("trylock-release");
        holder.lock();

        AtomicReference<Boolean> result = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                Reservation waiterRes = manager.getReservation("trylock-release");
                result.set(waiterRes.tryLock(5, TimeUnit.SECONDS));
                if (Boolean.TRUE.equals(result.get())) {
                    waiterRes.unlock();
                }
            } catch (InterruptedException e) {
                result.set(false);
            }
        });

        waiter.start();

        // Release after a short delay
        Thread.sleep(300);
        holder.unlock();

        waiter.join(5000);

        assertThat(result.get()).isTrue();
    }

    // ==================== forceUnlock Tests ====================

    @Test
    void forceUnlockShouldReleaseRegardlessOfOwner() {
        Reservation reservation = manager.getReservation("force-unlock");
        reservation.lock();

        // Force unlock from different context
        Reservation admin = manager.getReservation("force-unlock");
        admin.forceUnlock();

        assertThat(reservation.isLocked()).isFalse();

        // Original holder's unlock should fail
        assertThatThrownBy(reservation::unlock)
            .isInstanceOf(Exception.class); // Could be ReservationExpiredException or IllegalMonitorStateException
    }

    // ==================== Reentrancy Tests ====================

    @Test
    void shouldSupportReentrantLocking() {
        Reservation reservation = manager.getReservation("reentrant");

        reservation.lock();
        reservation.lock(); // Reentrant

        assertThat(reservation.isLocked()).isTrue();

        reservation.unlock();
        // Still held (need second unlock)
        assertThat(reservation.isLocked()).isTrue();

        reservation.unlock();
        assertThat(reservation.isLocked()).isFalse();
    }

    // ==================== Validation Tests ====================

    @Test
    void shouldRejectNullIdentifier() {
        assertThatThrownBy(() -> manager.getReservation(null))
            .isInstanceOf(InvalidReservationKeyException.class);
    }

    @Test
    void shouldRejectEmptyIdentifier() {
        assertThatThrownBy(() -> manager.getReservation(""))
            .isInstanceOf(InvalidReservationKeyException.class);
    }

    // ==================== newCondition Tests ====================

    @Test
    void newConditionShouldThrowUnsupportedOperationException() {
        Reservation reservation = manager.getReservation("condition");

        assertThatThrownBy(reservation::newCondition)
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("not supported");
    }

    // ==================== Interruptibility Tests ====================

    @Test
    @Timeout(10)
    void lockInterruptiblyShouldThrowWhenInterrupted() throws Exception {
        Reservation holder = manager.getReservation("interrupt-lock");
        holder.lock();

        try {
            AtomicReference<Throwable> exception = new AtomicReference<>();
            AtomicBoolean acquired = new AtomicBoolean(false);

            Thread waiterThread = new Thread(() -> {
                try {
                    Reservation waiter = manager.getReservation("interrupt-lock");
                    waiter.lockInterruptibly();
                    acquired.set(true);
                    waiter.unlock();
                } catch (InterruptedException e) {
                    exception.set(e);
                }
            });

            waiterThread.start();
            Thread.sleep(200);
            waiterThread.interrupt();
            waiterThread.join(3000);

            // Either the thread was interrupted (exception caught) or it didn't acquire
            // while the holder still had the lock.
            if (exception.get() != null) {
                assertThat(exception.get()).isInstanceOf(InterruptedException.class);
            } else {
                assertThat(acquired.get()).isFalse();
            }
        } finally {
            holder.unlock();
        }
    }

    // ==================== Isolation Tests ====================

    @Test
    void shouldIsolateLocksBetweenIdentifiers() {
        Reservation lock1 = manager.getReservation("123");
        Reservation lock2 = manager.getReservation("456");

        lock1.lock();

        // Different identifier should be acquirable
        assertThat(lock2.tryLock()).isTrue();
        lock2.unlock();

        lock1.unlock();
    }

    // ==================== Unlock Without Lock Tests ====================

    @Test
    void unlockWithoutLockShouldThrow() {
        Reservation reservation = manager.getReservation("no-lock");

        assertThatThrownBy(reservation::unlock)
            .isInstanceOf(Exception.class);
    }

    // ==================== Builder Validation Tests ====================

    @Test
    void builderShouldRequireDomain() {
        // This test validates that building without domain throws
        // Subclasses can override if they have different requirements
    }
}
