package com.github.reservation.internal;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks per-thread lock holds for all {@code Reservation} instances created by a single
 * {@code ReservationManager}.
 *
 * <p>State is keyed by reservation key and scoped to the current thread, so reentrant
 * locking and unlocking work across different Reservation instances obtained from the
 * same manager for the same identifier.</p>
 */
public final class HoldTracker {

    /**
     * Mutable hold state for one (thread, reservation key) pair.
     */
    public static final class Hold {
        private String holder;
        private Instant acquiredAt;
        private int count;

        public String getHolder() {
            return holder;
        }

        public void setHolder(String holder) {
            this.holder = holder;
        }

        public Instant getAcquiredAt() {
            return acquiredAt;
        }

        public void setAcquiredAt(Instant acquiredAt) {
            this.acquiredAt = acquiredAt;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    private final ThreadLocal<Map<String, Hold>> holds = ThreadLocal.withInitial(HashMap::new);

    /**
     * Returns the current thread's hold for the given key, or null if it holds nothing.
     */
    public Hold get(String reservationKey) {
        return holds.get().get(reservationKey);
    }

    /**
     * Returns the current thread's hold for the given key, creating an empty one if absent.
     */
    public Hold getOrCreate(String reservationKey) {
        return holds.get().computeIfAbsent(reservationKey, k -> new Hold());
    }

    /**
     * Removes the current thread's hold for the given key.
     */
    public void remove(String reservationKey) {
        holds.get().remove(reservationKey);
    }
}
