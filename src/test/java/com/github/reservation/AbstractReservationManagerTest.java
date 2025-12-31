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

    protected ReservationManager manager;

    /**
     * Creates the ReservationManager implementation to test.
     *
     * @param leaseTime the lease time for reservations
     * @return a new ReservationManager instance
     */
    protected abstract ReservationManager createManager(Duration leaseTime);

    /**
     * Cleans up resources after each test.
     */
    protected abstract void cleanup();

    @BeforeEach
    void setUp() {
        manager = createManager(Duration.ofSeconds(5));
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
        Reservation reservation = manager.getReservation("orders", "123");

        reservation.lock();
        assertThat(reservation.isLocked()).isTrue();

        reservation.unlock();
        assertThat(reservation.isLocked()).isFalse();
    }

    @Test
    void shouldReturnCorrectDomainAndIdentifier() {
        Reservation reservation = manager.getReservation("orders", "456");

        assertThat(reservation.getDomain()).isEqualTo("orders");
        assertThat(reservation.getIdentifier()).isEqualTo("456");
        assertThat(reservation.getReservationKey()).isEqualTo("orders::456");
    }

    @Test
    void shouldReturnConfiguredLeaseTime() {
        assertThat(manager.getLeaseTime()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void shouldReturnConfiguredDelimiter() {
        assertThat(manager.getDelimiter()).isEqualTo("::");
    }

    // ==================== Expiration Tests ====================

    @Test
    @Timeout(10)
    void shouldExpireAfterLeaseTime() throws Exception {
        // Use short lease for this test
        ReservationManager shortLeaseManager = createManager(Duration.ofSeconds(2));
        try {
            Reservation reservation = shortLeaseManager.getReservation("orders", "expire-test");

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
        Reservation reservation = manager.getReservation("orders", "lease-test");
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
        Reservation reservation = manager.getReservation("orders", "concurrent");
        reservation.lock();

        try {
            CompletableFuture<Boolean> otherThread = CompletableFuture.supplyAsync(() -> {
                Reservation sameReservation = manager.getReservation("orders", "concurrent");
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
        Reservation reservation1 = manager.getReservation("orders", "release-test");
        reservation1.lock();
        reservation1.unlock();

        // Different thread should now be able to acquire
        CompletableFuture<Boolean> otherThread = CompletableFuture.supplyAsync(() -> {
            Reservation reservation2 = manager.getReservation("orders", "release-test");
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
        ReservationManager shortLeaseManager = createManager(Duration.ofSeconds(1));
        try {
            Reservation reservation = shortLeaseManager.getReservation("orders", "expiry-acquire-test");

            reservation.lock();
            Thread.sleep(1500); // Wait for expiry

            // Another acquisition should succeed
            AtomicBoolean acquired = new AtomicBoolean(false);
            Thread otherThread = new Thread(() -> {
                Reservation newReservation = shortLeaseManager.getReservation("orders", "expiry-acquire-test");
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
        Reservation reservation = manager.getReservation("orders", "trylock-available");

        assertThat(reservation.tryLock()).isTrue();
        assertThat(reservation.isLocked()).isTrue();

        reservation.unlock();
    }

    @Test
    @Timeout(5)
    void tryLockShouldReturnFalseWhenUnavailable() throws Exception {
        Reservation holder = manager.getReservation("orders", "trylock-unavailable");
        holder.lock();

        try {
            CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(() -> {
                Reservation waiterRes = manager.getReservation("orders", "trylock-unavailable");
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
        Reservation holder = manager.getReservation("orders", "trylock-timeout");
        holder.lock();

        try {
            CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(() -> {
                try {
                    Reservation waiterRes = manager.getReservation("orders", "trylock-timeout");
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
        Reservation holder = manager.getReservation("orders", "trylock-release");
        holder.lock();

        AtomicReference<Boolean> result = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                Reservation waiterRes = manager.getReservation("orders", "trylock-release");
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
        Reservation reservation = manager.getReservation("orders", "force-unlock");
        reservation.lock();

        // Force unlock from different context
        Reservation admin = manager.getReservation("orders", "force-unlock");
        admin.forceUnlock();

        assertThat(reservation.isLocked()).isFalse();

        // Original holder's unlock should fail
        assertThatThrownBy(reservation::unlock)
            .isInstanceOf(Exception.class); // Could be ReservationExpiredException or IllegalMonitorStateException
    }

    // ==================== Reentrancy Tests ====================

    @Test
    void shouldSupportReentrantLocking() {
        Reservation reservation = manager.getReservation("orders", "reentrant");

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
    void shouldRejectNullDomain() {
        assertThatThrownBy(() -> manager.getReservation(null, "123"))
            .isInstanceOf(InvalidReservationKeyException.class);
    }

    @Test
    void shouldRejectEmptyDomain() {
        assertThatThrownBy(() -> manager.getReservation("", "123"))
            .isInstanceOf(InvalidReservationKeyException.class);
    }

    @Test
    void shouldRejectDomainContainingDelimiter() {
        assertThatThrownBy(() -> manager.getReservation("order::type", "123"))
            .isInstanceOf(InvalidReservationKeyException.class);
    }

    @Test
    void shouldRejectNullIdentifier() {
        assertThatThrownBy(() -> manager.getReservation("orders", null))
            .isInstanceOf(InvalidReservationKeyException.class);
    }

    @Test
    void shouldRejectEmptyIdentifier() {
        assertThatThrownBy(() -> manager.getReservation("orders", ""))
            .isInstanceOf(InvalidReservationKeyException.class);
    }

    @Test
    void shouldRejectIdentifierContainingDelimiter() {
        assertThatThrownBy(() -> manager.getReservation("orders", "123::456"))
            .isInstanceOf(InvalidReservationKeyException.class);
    }

    // ==================== newCondition Tests ====================

    @Test
    void newConditionShouldThrowUnsupportedOperationException() {
        Reservation reservation = manager.getReservation("orders", "condition");

        assertThatThrownBy(reservation::newCondition)
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("not supported");
    }

    // ==================== Interruptibility Tests ====================

    @Test
    @Timeout(10)
    void tryLockShouldThrowWhenInterrupted() throws Exception {
        Reservation holder = manager.getReservation("orders", "interrupt-trylock");
        holder.lock();

        try {
            AtomicReference<Throwable> exception = new AtomicReference<>();

            Thread waiterThread = new Thread(() -> {
                try {
                    Reservation waiter = manager.getReservation("orders", "interrupt-trylock");
                    waiter.tryLock(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    exception.set(e);
                }
            });

            waiterThread.start();
            Thread.sleep(200);
            waiterThread.interrupt();
            waiterThread.join(2000);

            assertThat(exception.get()).isInstanceOf(InterruptedException.class);
        } finally {
            holder.unlock();
        }
    }

    // ==================== Multiple Domains Tests ====================

    @Test
    void shouldIsolateLocksBetweenDomains() {
        Reservation ordersLock = manager.getReservation("orders", "123");
        Reservation usersLock = manager.getReservation("users", "123");

        ordersLock.lock();

        // Different domain should be acquirable
        assertThat(usersLock.tryLock()).isTrue();
        usersLock.unlock();

        ordersLock.unlock();
    }

    @Test
    void shouldIsolateLocksBetweenIdentifiers() {
        Reservation lock1 = manager.getReservation("orders", "123");
        Reservation lock2 = manager.getReservation("orders", "456");

        lock1.lock();

        // Different identifier should be acquirable
        assertThat(lock2.tryLock()).isTrue();
        lock2.unlock();

        lock1.unlock();
    }

    // ==================== Unlock Without Lock Tests ====================

    @Test
    void unlockWithoutLockShouldThrow() {
        Reservation reservation = manager.getReservation("orders", "no-lock");

        assertThatThrownBy(reservation::unlock)
            .isInstanceOf(Exception.class);
    }
}
