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
 *
 * <p>Lifecycle: the per-thread map is created lazily on the first real hold and removed
 * as soon as it becomes empty, so pooled threads do not retain empty maps (which would
 * also pin the classloader in redeployable containers). One bounded leak remains by
 * design: a hold whose lease expired without an unlock stays in its thread's map until
 * that same thread next touches the same identifier — the manager cannot reach other
 * threads' ThreadLocal state to purge it.</p>
 */
public final class HoldTracker {

    /**
     * Mutable hold state for one (thread, reservation key) pair.
     */
    public static final class Hold {
        private Instant acquiredAt;
        private int count;

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

        public void increment() {
            count++;
        }

        public void decrement() {
            count--;
        }
    }

    // Plain ThreadLocal, not withInitial: read paths must never instantiate per-thread
    // state, or every thread that ever checks a reservation would leak an empty map.
    private final ThreadLocal<Map<String, Hold>> holds = new ThreadLocal<>();

    /**
     * Returns the current thread's hold for the given key, or null if it holds nothing.
     * Never instantiates per-thread state.
     */
    public Hold get(String reservationKey) {
        Map<String, Hold> map = holds.get();
        return map == null ? null : map.get(reservationKey);
    }

    /**
     * Returns the current thread's hold for the given key, creating an empty one if absent.
     */
    public Hold getOrCreate(String reservationKey) {
        Map<String, Hold> map = holds.get();
        if (map == null) {
            map = new HashMap<>();
            holds.set(map);
        }
        return map.computeIfAbsent(reservationKey, k -> new Hold());
    }

    /**
     * Removes the current thread's hold for the given key, discarding the per-thread
     * map entirely once it is empty.
     */
    public void remove(String reservationKey) {
        Map<String, Hold> map = holds.get();
        if (map == null) {
            return;
        }
        map.remove(reservationKey);
        if (map.isEmpty()) {
            holds.remove();
        }
    }
}
