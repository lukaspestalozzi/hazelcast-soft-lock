# Reservation Lock Library - Design Document

> **Version**: 1.0.0-SNAPSHOT
> **Status**: Draft
> **Last Updated**: 2025-12-31

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [API Design](#3-api-design)
4. [Hazelcast Implementation](#4-hazelcast-implementation)
5. [Configuration](#5-configuration)
6. [Error Handling](#6-error-handling)
7. [Observability](#7-observability)
8. [Testing Strategy](#8-testing-strategy)
9. [Performance Considerations](#9-performance-considerations)
10. [Project Structure](#10-project-structure)
11. [Dependencies](#11-dependencies)
12. [Roadmap](#12-roadmap)

---

## 1. Overview

### 1.1 Purpose

This library provides a **Reservation** (soft-lock) implementation for distributed Java applications. A reservation is a distributed lock that **automatically expires** after a configurable lease time (default: 1 minute), preventing deadlocks caused by crashed processes or forgotten unlocks.

The library ships a single backend implementation, **Hazelcast**, using `IMap.lock()` with native lease time support. The public API is backend-agnostic so additional backends can be added later; any future implementation must share the same API and behavioral guarantees.

### 1.2 Key Features

- Implements `java.util.concurrent.locks.Lock` interface for familiarity
- Automatic lock expiration via configurable lease time
- **Single-domain managers**: Each ReservationManager handles one domain
- Lock identity composed of **domain** (from manager) and **identifier** (per reservation)
- Backend-agnostic API with Hazelcast as the provided backend
- **Domain isolation**: separate IMaps per domain
- Micrometer metrics integration for observability
- Reentrant locking support
- Dedicated exception hierarchy (unchecked, as required by the `Lock` interface)
- Shared test suite validating any implementation against the same contract

### 1.3 Design Decisions Summary

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Naming | `Reservation` (not SoftLock) | Domain-appropriate terminology |
| Lock interface | `Reservation extends Lock` | Compatibility + additional methods |
| `newCondition()` | `UnsupportedOperationException` | Not feasible for distributed locks |
| Domain per manager | Single domain per ReservationManager | Cleaner API, explicit isolation |
| Domain isolation | Separate IMap per domain | Backend-appropriate isolation |
| Hazelcast map naming | `{mapPrefix}-{domain}` | Domain isolation via separate maps |
| Lease time config | Global default on manager | Simplicity with configurability |
| Thread affinity | Strict (per-thread ownership) | Consistency with Lock contract |
| Reentrancy | Supported | Same thread can lock multiple times |
| Error handling | Dedicated unchecked exception hierarchy | `Lock` methods cannot declare checked exceptions |
| Project structure | Single module | Simpler build, single artifact |
| Testing | Abstract base test class | Shared contract tests for all implementations |
| Hazelcast value | String with debug info | Debuggability with low overhead |

---

## 2. Architecture

### 2.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Application Code                               │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
┌───────────────────────┐ ┌───────────────────────┐ ┌───────────────────────┐
│ ReservationManager    │ │ ReservationManager    │ │ ReservationManager    │
│ domain="orders"       │ │ domain="users"        │ │ domain="inventory"    │
│  ┌─────────────────┐  │ │  ┌─────────────────┐  │ │  ┌─────────────────┐  │
│  │ getReservation  │  │ │  │ getReservation  │  │ │  │ getReservation  │  │
│  │ (identifier)    │  │ │  │ (identifier)    │  │ │  │ (identifier)    │  │
│  └─────────────────┘  │ │  └─────────────────┘  │ │  └─────────────────┘  │
└───────────────────────┘ └───────────────────────┘ └───────────────────────┘
          │                         │                         │
          ▼                         ▼                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                             Reservation                                  │
│         Implements: java.util.concurrent.locks.Lock                      │
│         Additional: identifier, reservationKey, remainingLeaseTime       │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
                    ┌───────────────────────────────┐
                    │   HazelcastReservationManager │
                    │   ┌─────────────────────────┐ │
                    │   │ IMap<String, String>    │ │
                    │   │ - lock(key, lease)      │ │
                    │   │ - tryLock(...)          │ │
                    │   │ - unlock(key)           │ │
                    │   │ - value: debug string   │ │
                    │   └─────────────────────────┘ │
                    └───────────────────────────────┘
                                    │
                                    ▼
                    ┌───────────────────────────────┐
                    │     Hazelcast Cluster         │
                    └───────────────────────────────┘
```

Additional backends can be added by implementing `Reservation` and `ReservationManager` against a different storage layer.

### 2.2 Component Responsibilities

| Component | Responsibility |
|-----------|----------------|
| `ReservationManager` | Interface for creating reservations for a single domain |
| `Reservation` | Interface for individual lock instance, extends Lock |
| `HazelcastReservationManager` | Hazelcast-backed implementation (uses domain-specific IMap) |
| `ReservationMetrics` | Micrometer metrics registration and recording |

### 2.3 Reservation Lifecycle

```
┌──────────┐  getReservation()  ┌──────────┐
│  START   │───────────────────▶│ CREATED  │
└──────────┘                    └──────────┘
                                      │
                       lock() / tryLock()
                                      ▼
                                ┌──────────┐
                 ┌──────────────│ ACQUIRED │◀─────────────┐
                 │              └──────────┘              │
                 │                    │                   │
            unlock()            lease expires       reentrant
                 │                    │              lock()
                 ▼                    ▼                   │
            ┌──────────┐        ┌──────────┐             │
            │ RELEASED │        │ EXPIRED  │─────────────┘
            └──────────┘        └──────────┘
                                      │
                                unlock() after expiry
                                      ▼
                             ┌─────────────────┐
                             │ LockExpired     │
                             │ Exception       │
                             └─────────────────┘
```

---

## 3. API Design

### 3.1 Core Interfaces

#### 3.1.1 Reservation Interface

```java
package com.github.reservation;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * A distributed reservation (soft-lock) that automatically expires after a configured lease time.
 *
 * <p>A reservation is obtained from a {@link ReservationManager} which is bound to a specific
 * domain. The reservation is identified by an identifier within that domain.</p>
 *
 * <p><b>Important:</b> The {@link #newCondition()} method is not supported for
 * distributed locks and will throw {@link UnsupportedOperationException}.</p>
 *
 * <p><b>Warning:</b> If the lease time expires while the reservation is held, calling
 * {@link #unlock()} will throw {@link ReservationExpiredException}. This indicates that
 * the critical section guarantee may have been violated.</p>
 *
 * <p><b>Reentrancy:</b> This lock supports reentrant locking - the same thread can
 * acquire the same lock multiple times without blocking.</p>
 */
public interface Reservation extends Lock {

    /**
     * Returns the identifier of this reservation within its domain.
     *
     * @return the identifier string, never null
     */
    String getIdentifier();

    /**
     * Returns the key used for this reservation in the underlying storage.
     * <p>For Hazelcast: just the identifier (domain isolation via separate maps).
     *
     * @return the reservation key string, never null
     */
    String getReservationKey();

    /**
     * Returns the remaining lease time for this reservation.
     *
     * @return remaining lease time, or {@link Duration#ZERO} if not held
     *         or lease has expired
     */
    Duration getRemainingLeaseTime();

    /**
     * Checks if this reservation is currently held by any thread/process.
     *
     * @return true if the reservation is held, false otherwise
     */
    boolean isLocked();

    /**
     * Forces the release of this reservation regardless of ownership.
     *
     * <p><b>Warning:</b> This is an administrative operation that should only
     * be used for recovery scenarios. It will release the reservation even if held
     * by another thread or process.</p>
     */
    void forceUnlock();

    // --- Lock interface methods with reservation semantics ---

    /**
     * Acquires the reservation, blocking until available.
     * The reservation will automatically be released after the configured lease time.
     * Supports reentrant locking.
     *
     * @throws ReservationAcquisitionException if the reservation cannot be acquired
     */
    @Override
    void lock() throws ReservationAcquisitionException;

    /**
     * Acquires the reservation unless the current thread is interrupted.
     * Supports reentrant locking.
     *
     * @throws InterruptedException if the current thread is interrupted
     * @throws ReservationAcquisitionException if the reservation cannot be acquired
     */
    @Override
    void lockInterruptibly() throws InterruptedException, ReservationAcquisitionException;

    /**
     * Acquires the reservation only if it is free at the time of invocation.
     * Supports reentrant locking.
     *
     * @return true if the reservation was acquired, false otherwise
     */
    @Override
    boolean tryLock();

    /**
     * Acquires the reservation if it becomes available within the given waiting time.
     * Supports reentrant locking.
     *
     * @param time the maximum time to wait for the reservation
     * @param unit the time unit of the time argument
     * @return true if the reservation was acquired, false if the waiting time elapsed
     * @throws InterruptedException if the current thread is interrupted
     */
    @Override
    boolean tryLock(long time, TimeUnit unit) throws InterruptedException;

    /**
     * Releases the reservation. For reentrant locks, decrements the hold count.
     *
     * @throws ReservationExpiredException if the lease time has expired before unlock
     * @throws IllegalMonitorStateException if the current thread does not hold the reservation
     */
    @Override
    void unlock() throws ReservationExpiredException;

    /**
     * Not supported for distributed reservations.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    default Condition newCondition() {
        throw new UnsupportedOperationException(
            "Conditions are not supported for distributed reservations. " +
            "Consider using a distributed coordination service for complex synchronization.");
    }
}
```

#### 3.1.2 ReservationManager Interface

```java
package com.github.reservation;

import java.io.Closeable;
import java.time.Duration;

/**
 * Factory and manager for {@link Reservation} instances within a single domain.
 *
 * <p>A ReservationManager is bound to a specific backend, a single domain,
 * and configuration. Create separate managers for different domains.</p>
 *
 * <p>Example usage with Hazelcast:</p>
 * <pre>{@code
 * HazelcastInstance hz = HazelcastClient.newHazelcastClient();
 *
 * ReservationManager ordersManager = ReservationManager.hazelcast(hz)
 *     .domain("orders")
 *     .leaseTime(Duration.ofMinutes(2))
 *     .build();
 *
 * Reservation reservation = ordersManager.getReservation("12345");
 * reservation.lock();
 * try {
 *     // critical section
 * } finally {
 *     reservation.unlock();
 * }
 * }</pre>
 *
 * <p>Multiple domains require multiple managers:</p>
 * <pre>{@code
 * ReservationManager ordersManager = ReservationManager.hazelcast(hz)
 *     .domain("orders").build();
 * ReservationManager usersManager = ReservationManager.hazelcast(hz)
 *     .domain("users").build();
 *
 * // Each uses isolated storage (separate IMap for Hazelcast)
 * ordersManager.getReservation("123").lock();  // Uses map "reservations-orders"
 * usersManager.getReservation("123").lock();   // Uses map "reservations-users"
 * }</pre>
 */
public interface ReservationManager extends Closeable {

    /**
     * Creates a new builder for a Hazelcast-backed ReservationManager.
     *
     * @param hazelcastInstance the Hazelcast client instance to use
     * @return a new builder instance
     * @throws NullPointerException if hazelcastInstance is null
     */
    static HazelcastReservationManagerBuilder hazelcast(
            com.hazelcast.core.HazelcastInstance hazelcastInstance) {
        return new HazelcastReservationManagerBuilder(hazelcastInstance);
    }

    /**
     * Obtains a reservation for the given identifier within this manager's domain.
     *
     * <p>This method always returns a new Reservation instance, but the underlying
     * distributed lock is shared across all instances with the same identifier.</p>
     *
     * @param identifier the identifier within the domain (e.g., order ID, user ID)
     * @return a Reservation instance for the given identifier
     * @throws InvalidReservationKeyException if identifier is null or empty
     */
    Reservation getReservation(String identifier);

    /**
     * Returns the domain this manager handles.
     *
     * @return the domain string
     */
    String getDomain();

    /**
     * Returns the configured lease time for reservations created by this manager.
     *
     * @return the lease time duration
     */
    Duration getLeaseTime();

    /**
     * Closes this manager and releases associated resources.
     *
     * <p>Note: This does NOT close the underlying Hazelcast instance,
     * nor does it release any currently held reservations.</p>
     */
    @Override
    void close();
}
```

### 3.2 Exception Hierarchy

```java
package com.github.reservation;

/**
 * Base exception for all reservation-related errors.
 */
public class ReservationException extends Exception {
    public ReservationException(String message) { super(message); }
    public ReservationException(String message, Throwable cause) { super(message, cause); }
}

/**
 * Thrown when a reservation cannot be acquired.
 */
public class ReservationAcquisitionException extends ReservationException {
    private final String domain;
    private final String identifier;

    public ReservationAcquisitionException(String domain, String identifier, String message) {
        super(message);
        this.domain = domain;
        this.identifier = identifier;
    }

    public ReservationAcquisitionException(String domain, String identifier,
                                           String message, Throwable cause) {
        super(message, cause);
        this.domain = domain;
        this.identifier = identifier;
    }

    public String getDomain() { return domain; }
    public String getIdentifier() { return identifier; }
}

/**
 * Thrown when attempting to unlock a reservation whose lease has already expired.
 *
 * <p>This exception indicates a potential violation of the critical section
 * guarantee - another process may have acquired the reservation after expiration.</p>
 */
public class ReservationExpiredException extends ReservationException {
    private final String domain;
    private final String identifier;

    public ReservationExpiredException(String domain, String identifier) {
        super(String.format(
            "Reservation [%s::%s] lease expired before unlock. " +
            "Critical section guarantee may be violated.",
            domain, identifier));
        this.domain = domain;
        this.identifier = identifier;
    }

    public String getDomain() { return domain; }
    public String getIdentifier() { return identifier; }
}

/**
 * Thrown when invalid arguments are provided (e.g., delimiter in domain/identifier).
 */
public class InvalidReservationKeyException extends IllegalArgumentException {
    public InvalidReservationKeyException(String message) { super(message); }
}
```

### 3.3 Builder Classes

#### 3.3.1 Base Builder (Shared Configuration)

```java
package com.github.reservation;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;

/**
 * Base builder with common configuration for all ReservationManager implementations.
 */
public abstract class AbstractReservationManagerBuilder<T extends AbstractReservationManagerBuilder<T>> {

    protected String domain;  // Required
    protected Duration leaseTime = Duration.ofMinutes(1);
    protected MeterRegistry meterRegistry = null;

    /**
     * Sets the domain for this manager. Required.
     *
     * @param domain the domain name (e.g., "orders", "users")
     * @return this builder
     * @throws NullPointerException if domain is null
     * @throws IllegalArgumentException if domain is empty
     */
    @SuppressWarnings("unchecked")
    public T domain(String domain) {
        Objects.requireNonNull(domain, "domain must not be null");
        if (domain.isEmpty()) {
            throw new IllegalArgumentException("domain must not be empty");
        }
        this.domain = domain;
        return (T) this;
    }

    /**
     * Sets the lease time for reservations. Default: 1 minute.
     */
    @SuppressWarnings("unchecked")
    public T leaseTime(Duration leaseTime) {
        Objects.requireNonNull(leaseTime, "leaseTime must not be null");
        if (leaseTime.isZero() || leaseTime.isNegative()) {
            throw new IllegalArgumentException("leaseTime must be positive");
        }
        this.leaseTime = leaseTime;
        return (T) this;
    }

    /**
     * Sets the Micrometer registry for metrics. Default: none (metrics disabled)
     */
    @SuppressWarnings("unchecked")
    public T meterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        return (T) this;
    }

    /**
     * Validates that required fields are set.
     *
     * @throws IllegalStateException if domain is not set
     */
    protected void validate() {
        if (domain == null) {
            throw new IllegalStateException("domain must be set before building");
        }
    }

    /**
     * Builds the ReservationManager with the configured settings.
     *
     * @throws IllegalStateException if domain is not set
     */
    public abstract ReservationManager build();
}
```

#### 3.3.2 Hazelcast Builder

```java
package com.github.reservation.hazelcast;

import com.github.reservation.AbstractReservationManagerBuilder;
import com.github.reservation.ReservationManager;
import com.hazelcast.core.HazelcastInstance;
import java.util.Objects;

/**
 * Builder for creating Hazelcast-backed {@link ReservationManager} instances.
 *
 * <p>Each manager is bound to a single domain. The Hazelcast IMap name is
 * derived from the mapPrefix and domain: "{mapPrefix}-{domain}".</p>
 */
public final class HazelcastReservationManagerBuilder
        extends AbstractReservationManagerBuilder<HazelcastReservationManagerBuilder> {

    private final HazelcastInstance hazelcastInstance;
    private String mapPrefix = "reservations";

    HazelcastReservationManagerBuilder(HazelcastInstance hazelcastInstance) {
        this.hazelcastInstance = Objects.requireNonNull(hazelcastInstance,
            "hazelcastInstance must not be null");
    }

    /**
     * Sets the prefix for Hazelcast IMap name. Default: "reservations"
     *
     * <p>The actual map name will be "{mapPrefix}-{domain}", e.g.,
     * "reservations-orders" for domain "orders".</p>
     */
    public HazelcastReservationManagerBuilder mapPrefix(String mapPrefix) {
        Objects.requireNonNull(mapPrefix, "mapPrefix must not be null");
        if (mapPrefix.isEmpty()) {
            throw new IllegalArgumentException("mapPrefix must not be empty");
        }
        this.mapPrefix = mapPrefix;
        return this;
    }

    @Override
    public ReservationManager build() {
        validate();  // Ensures domain is set
        String mapName = mapPrefix + "-" + domain;
        return new HazelcastReservationManager(
            hazelcastInstance,
            domain,
            leaseTime,
            mapName,
            meterRegistry
        );
    }
}
```

---

## 4. Hazelcast Implementation

### 4.1 Overview

The Hazelcast implementation uses `IMap.lock()` with native lease time support. Each domain uses a separate IMap for isolation, with the map name derived from `{mapPrefix}-{domain}`.

For debuggability, it stores a value in the map when a lock is acquired.

### 4.2 IMap Value Format

Values are stored as simple key-value strings for debugging:

```
holder=thread-123@host-abc,acquired=2025-01-01T12:00:00.000Z
```

Implementation:

```java
/**
 * Builds the debug value stored in the IMap.
 */
final class ReservationValueBuilder {

    static String buildValue() {
        String threadName = Thread.currentThread().getName();
        String hostName = getHostName();
        Instant acquired = Instant.now();

        return String.format("holder=%s@%s,acquired=%s",
            threadName, hostName, acquired.toString());
    }

    private static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
```

### 4.3 Lock Operations

```java
class HazelcastReservation implements Reservation {

    private final IMap<String, String> lockMap;
    private final String domain;        // For metrics/logging
    private final String identifier;    // Key in the map
    private final Duration leaseTime;

    @Override
    public void lock() throws ReservationAcquisitionException {
        try {
            // First acquire the Hazelcast lock (key = identifier only)
            lockMap.lock(identifier, leaseTime.toMillis(), TimeUnit.MILLISECONDS);

            // Then store debug value
            String value = buildDebugValue();
            lockMap.set(identifier, value, leaseTime.toMillis(), TimeUnit.MILLISECONDS);

        } catch (Exception e) {
            throw new ReservationAcquisitionException(domain, identifier,
                "Failed to acquire reservation", e);
        }
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        boolean acquired = lockMap.tryLock(
            identifier,
            time, unit,                                    // wait time
            leaseTime.toMillis(), TimeUnit.MILLISECONDS    // lease time
        );

        if (acquired) {
            String value = buildDebugValue();
            lockMap.set(identifier, value, leaseTime.toMillis(), TimeUnit.MILLISECONDS);
        }

        return acquired;
    }

    @Override
    public void unlock() {
        // Per-thread hold tracking (shared per manager) distinguishes
        // "never held" from "lease expired"
        if (currentThreadHoldCount() == 0) {
            throw new IllegalMonitorStateException(...);
        }
        try {
            if (currentThreadHoldCount() == 1) {
                // Clean up debug value while still owning the lock; zero timeout
                // so an expired holder never blocks on the new holder
                lockMap.tryRemove(identifier, 0, TimeUnit.MILLISECONDS);
            }
            lockMap.unlock(identifier);
        } catch (IllegalMonitorStateException e) {
            // Lease expired before unlock
            throw new ReservationExpiredException(domain, identifier);
        }
    }

    @Override
    public void forceUnlock() {
        lockMap.forceUnlock(identifier);
        lockMap.tryRemove(identifier, 0, TimeUnit.MILLISECONDS);  // best-effort cleanup
    }

    @Override
    public String getReservationKey() {
        return identifier;  // Domain isolation via separate maps
    }
}
```

### 4.4 Thread Safety

- `HazelcastReservationManager`: Immutable after construction, thread-safe
- `HazelcastReservation`: Thread-safe via Hazelcast's guarantees
- IMap operations are atomic

---

## 5. Configuration

### 5.1 Common Configuration Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `domain` | `String` | **required** | Domain for this manager |
| `leaseTime` | `Duration` | 1 minute | Time after which reservation auto-releases |
| `meterRegistry` | `MeterRegistry` | `null` | Micrometer registry (null = no metrics) |

### 5.2 Hazelcast-Specific Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `mapPrefix` | `String` | `reservations` | Prefix for IMap name (actual: `{prefix}-{domain}`) |

### 5.3 Hazelcast Client Setup Example

```java
ClientConfig config = new ClientConfig();
config.setClusterName("my-cluster");
config.getNetworkConfig().addAddress("hazelcast-node1:5701", "hazelcast-node2:5701");

config.getConnectionStrategyConfig()
    .getConnectionRetryConfig()
    .setClusterConnectTimeoutMillis(30000);

HazelcastInstance hz = HazelcastClient.newHazelcastClient(config);

// Create manager for "orders" domain (uses map "reservations-orders")
ReservationManager ordersManager = ReservationManager.hazelcast(hz)
    .domain("orders")
    .leaseTime(Duration.ofMinutes(2))
    .build();

// Create manager for "users" domain (uses map "reservations-users")
ReservationManager usersManager = ReservationManager.hazelcast(hz)
    .domain("users")
    .leaseTime(Duration.ofMinutes(5))
    .build();
```

---

## 6. Error Handling

### 6.1 Exception Strategy

The `ReservationException` hierarchy extends `RuntimeException` because the
`java.util.concurrent.locks.Lock` interface does not allow checked exceptions:

| Method | Throws | Reason |
|--------|--------|--------|
| `lock()` | `ReservationAcquisitionException` | Network/database issues |
| `lockInterruptibly()` | `InterruptedException`, `ReservationAcquisitionException` | Interruption, infrastructure issues |
| `tryLock()` | (none - returns boolean) | Non-blocking, failure = false |
| `tryLock(time, unit)` | `InterruptedException` | Timeout = false, interruption = exception |
| `unlock()` | `ReservationExpiredException` | Lease expired before unlock |
| `newCondition()` | `UnsupportedOperationException` | Not supported |

### 6.2 Recovery Scenarios

| Scenario | Behavior |
|----------|----------|
| Network partition | Lock may expire; other node may acquire |
| Client/Process crash | Hazelcast releases (member death) |
| Lease expires during critical section | `ReservationExpiredException` on unlock |
| Backend unavailable | `ReservationAcquisitionException` |

---

## 7. Observability

### 7.1 Micrometer Metrics

| Metric Name | Type | Tags | Description |
|-------------|------|------|-------------|
| `reservation.acquire` | Timer | `domain`, `backend`, `result` | Acquisition time and outcome |
| `reservation.acquire.attempts` | Counter | `domain`, `backend`, `result` | Acquisition attempts |
| `reservation.held.time` | Timer | `domain`, `backend` | Duration reservation was held |
| `reservation.expired` | Counter | `domain`, `backend` | Reservations expired before unlock |
| `reservation.active` | Gauge | `domain`, `backend` | Currently held reservations (approximate) |

### 7.2 Metric Tags

- `domain`: The reservation domain (for grouping/filtering)
- `backend`: `hazelcast`
- `result`: `acquired`, `timeout`, `interrupted`, `error`

### 7.3 Metrics Implementation

```java
class ReservationMetrics {
    private final MeterRegistry registry;
    private final String backend;

    ReservationMetrics(MeterRegistry registry, String backend) {
        this.registry = registry;
        this.backend = backend;
    }

    void recordAcquisition(String domain, Duration elapsed, String result) {
        if (registry == null) return;

        Timer.builder("reservation.acquire")
            .tag("domain", domain)
            .tag("backend", backend)
            .tag("result", result)
            .register(registry)
            .record(elapsed);
    }

    void recordExpiration(String domain) {
        if (registry == null) return;

        Counter.builder("reservation.expired")
            .tag("domain", domain)
            .tag("backend", backend)
            .register(registry)
            .increment();
    }
}
```

---

## 8. Testing Strategy

### 8.1 Test Pyramid

```
                      ┌─────────────────┐
                      │   E2E Tests     │  ← Manual/staged (real infra)
                      │   (Few)         │
                      └────────┬────────┘
                               │
                      ┌────────▼────────┐
                      │  Integration    │  ← Testcontainers
                      │  Tests          │     (Hazelcast)
                      │  (Medium)       │
                      └────────┬────────┘
                               │
          ┌────────────────────▼────────────────────┐
          │              Unit Tests                  │  ← Embedded Hazelcast
          │              (Many)                      │
          └─────────────────────────────────────────┘
```

### 8.2 Shared Test Suite with Abstract Base Class

All implementations run the **same tests** via an abstract base class:

```java
package com.github.reservation;

import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Abstract base test class defining the contract for all ReservationManager implementations.
 *
 * <p>Subclasses provide the specific implementation to test.</p>
 */
abstract class AbstractReservationManagerTest {

    protected static final String DEFAULT_DOMAIN = "test-domain";
    protected ReservationManager manager;

    /**
     * Creates the ReservationManager implementation to test.
     * Called before each test.
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
    void shouldAcquireAndReleaseReservation() throws Exception {
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

    // ==================== Expiration Tests ====================

    @Test
    void shouldExpireAfterLeaseTime() throws Exception {
        // Use short lease for this test
        ReservationManager shortLeaseManager = createManager(DEFAULT_DOMAIN, Duration.ofSeconds(2));
        Reservation reservation = shortLeaseManager.getReservation("789");

        reservation.lock();
        assertThat(reservation.isLocked()).isTrue();

        // Wait for lease to expire
        Thread.sleep(3000);

        assertThat(reservation.isLocked()).isFalse();
        assertThatThrownBy(reservation::unlock)
            .isInstanceOf(ReservationExpiredException.class);

        shortLeaseManager.close();
    }

    @Test
    void shouldHavePositiveRemainingLeaseTimeAfterAcquire() throws Exception {
        Reservation reservation = manager.getReservation("lease-test");
        reservation.lock();

        Duration remaining = reservation.getRemainingLeaseTime();
        assertThat(remaining).isPositive();
        assertThat(remaining).isLessThanOrEqualTo(Duration.ofSeconds(5));

        reservation.unlock();
    }

    // ==================== Concurrency Tests ====================

    @Test
    void shouldBlockConcurrentAcquisition() throws Exception {
        Reservation reservation = manager.getReservation("concurrent");
        reservation.lock();

        CompletableFuture<Boolean> otherThread = CompletableFuture.supplyAsync(() -> {
            Reservation sameReservation = manager.getReservation("concurrent");
            return sameReservation.tryLock();
        });

        assertThat(otherThread.get(1, TimeUnit.SECONDS)).isFalse();
        reservation.unlock();
    }

    @Test
    void shouldAllowAcquisitionAfterRelease() throws Exception {
        Reservation reservation1 = manager.getReservation("release-test");
        reservation1.lock();
        reservation1.unlock();

        // Different thread should now be able to acquire
        CompletableFuture<Boolean> otherThread = CompletableFuture.supplyAsync(() -> {
            Reservation reservation2 = manager.getReservation("release-test");
            boolean acquired = reservation2.tryLock();
            if (acquired) {
                try {
                    reservation2.unlock();
                } catch (Exception e) {
                    // ignore
                }
            }
            return acquired;
        });

        assertThat(otherThread.get(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldAllowAcquisitionAfterExpiry() throws Exception {
        ReservationManager shortLeaseManager = createManager(DEFAULT_DOMAIN, Duration.ofSeconds(1));
        Reservation reservation = shortLeaseManager.getReservation("expiry-test");

        reservation.lock();
        Thread.sleep(1500); // Wait for expiry

        // Another acquisition should succeed
        CompletableFuture<Boolean> otherThread = CompletableFuture.supplyAsync(() -> {
            Reservation newReservation = shortLeaseManager.getReservation("expiry-test");
            boolean acquired = newReservation.tryLock();
            if (acquired) {
                try {
                    newReservation.unlock();
                } catch (Exception e) {
                    // ignore
                }
            }
            return acquired;
        });

        assertThat(otherThread.get(2, TimeUnit.SECONDS)).isTrue();
        shortLeaseManager.close();
    }

    // ==================== tryLock with Timeout Tests ====================

    @Test
    void tryLockShouldReturnTrueWhenAvailable() throws Exception {
        Reservation reservation = manager.getReservation("trylock-available");

        assertThat(reservation.tryLock(1, TimeUnit.SECONDS)).isTrue();
        assertThat(reservation.isLocked()).isTrue();

        reservation.unlock();
    }

    @Test
    void tryLockShouldReturnFalseAfterTimeout() throws Exception {
        Reservation holder = manager.getReservation("trylock-timeout");
        holder.lock();

        CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(() -> {
            try {
                Reservation waiterRes = manager.getReservation("trylock-timeout");
                return waiterRes.tryLock(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return false;
            }
        });

        assertThat(waiter.get(2, TimeUnit.SECONDS)).isFalse();
        holder.unlock();
    }

    // ==================== forceUnlock Tests ====================

    @Test
    void forceUnlockShouldReleaseRegardlessOfOwner() throws Exception {
        Reservation reservation = manager.getReservation("force-unlock");
        reservation.lock();

        // Force unlock from different context
        Reservation admin = manager.getReservation("force-unlock");
        admin.forceUnlock();

        assertThat(reservation.isLocked()).isFalse();

        // Original holder's unlock should fail
        assertThatThrownBy(reservation::unlock)
            .isInstanceOf(ReservationExpiredException.class);
    }

    // ==================== Reentrancy Tests ====================

    @Test
    void shouldSupportReentrantLocking() throws Exception {
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
    void lockInterruptiblyShouldThrowWhenInterrupted() throws Exception {
        Reservation holder = manager.getReservation("interrupt");
        holder.lock();

        Thread waiterThread = new Thread(() -> {
            try {
                Reservation waiter = manager.getReservation("interrupt");
                waiter.lockInterruptibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ReservationAcquisitionException e) {
                // expected when interrupted
            }
        });

        waiterThread.start();
        Thread.sleep(100);
        waiterThread.interrupt();
        waiterThread.join(1000);

        assertThat(waiterThread.isInterrupted() || !waiterThread.isAlive()).isTrue();
        holder.unlock();
    }

    // ==================== Domain Isolation Tests ====================

    @Test
    void shouldIsolateBetweenDomains() throws Exception {
        ReservationManager ordersManager = createManager("orders", Duration.ofSeconds(5));
        ReservationManager usersManager = createManager("users", Duration.ofSeconds(5));

        // Same identifier in different domains should be independent
        Reservation ordersLock = ordersManager.getReservation("123");
        Reservation usersLock = usersManager.getReservation("123");

        ordersLock.lock();
        assertThat(usersLock.tryLock()).isTrue(); // Should succeed, different domain

        ordersLock.unlock();
        usersLock.unlock();
        ordersManager.close();
        usersManager.close();
    }
}
```

### 8.3 Hazelcast Test Implementation

```java
package com.github.reservation.hazelcast;

import com.github.reservation.*;
import com.hazelcast.config.Config;
import com.hazelcast.core.*;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.UUID;

class HazelcastReservationManagerTest extends AbstractReservationManagerTest {

    private static HazelcastInstance hazelcast;
    private String mapName;

    @BeforeAll
    static void setupHazelcast() {
        Config config = new Config();
        config.setClusterName("test-" + UUID.randomUUID());
        hazelcast = Hazelcast.newHazelcastInstance(config);
    }

    @AfterAll
    static void teardownHazelcast() {
        if (hazelcast != null) {
            hazelcast.shutdown();
        }
    }

    @Override
    protected ReservationManager createManager(String domain, Duration leaseTime) {
        mapName = "reservations-" + domain;  // Derived from domain
        return ReservationManager.hazelcast(hazelcast)
            .domain(domain)
            .leaseTime(leaseTime)
            .build();
    }

    @Override
    protected void cleanup() {
        if (mapName != null && hazelcast != null) {
            hazelcast.getMap(mapName).destroy();
        }
    }
}
```

### 8.4 Integration Tests with Testcontainers

For full integration testing with real Hazelcast cluster:

```java
@Testcontainers
class HazelcastIntegrationTest extends AbstractReservationManagerTest {

    @Container
    static HazelcastContainer hazelcast = new HazelcastContainer("hazelcast/hazelcast:5.3");

    private HazelcastInstance client;
    private String mapName;

    @Override
    protected ReservationManager createManager(String domain, Duration leaseTime) {
        ClientConfig config = new ClientConfig();
        config.getNetworkConfig().addAddress(
            hazelcast.getHost() + ":" + hazelcast.getFirstMappedPort());
        client = HazelcastClient.newHazelcastClient(config);

        mapName = "reservations-" + domain;  // Derived from domain
        return ReservationManager.hazelcast(client)
            .domain(domain)
            .leaseTime(leaseTime)
            .build();
    }

    @Override
    protected void cleanup() {
        if (client != null) {
            client.shutdown();
        }
    }
}
```

---

## 9. Performance Considerations

### 9.1 Operation Latencies

| Operation | Hazelcast |
|-----------|-----------|
| `lock()` (uncontended) | < 1ms |
| `tryLock()` (uncontended) | < 1ms |
| `unlock()` | < 1ms |
| `isLocked()` | < 1ms |

### 9.2 Hazelcast Optimization

1. **Client Proximity**: Deploy clients close to Hazelcast nodes
2. **Lock Granularity**: Use fine-grained identifiers
3. **Map Partitioning**: Diverse keys = better distribution

---

## 10. Project Structure

```
reservation-lock/
├── pom.xml
├── README.md
├── docs/
│   └── DESIGN.md                              # This document
├── src/
│   ├── main/
│   │   └── java/
│   │       └── com/
│   │           └── github/
│   │               └── reservation/
│   │                   ├── Reservation.java                    # Main interface
│   │                   ├── ReservationManager.java             # Factory interface
│   │                   ├── AbstractReservationManagerBuilder.java
│   │                   ├── ReservationException.java           # Base exception
│   │                   ├── ReservationAcquisitionException.java
│   │                   ├── ReservationExpiredException.java
│   │                   ├── InvalidReservationKeyException.java
│   │                   ├── hazelcast/
│   │                   │   ├── HazelcastReservationManager.java
│   │                   │   ├── HazelcastReservation.java
│   │                   │   └── HazelcastReservationManagerBuilder.java
│   │                   └── internal/
│   │                       ├── HoldTracker.java
│   │                       ├── ReservationMetrics.java
│   │                       ├── MicrometerReservationMetrics.java
│   │                       └── NoOpReservationMetrics.java
│   └── test/
│       └── java/
│           └── com/
│               └── github/
│                   └── reservation/
│                       ├── AbstractReservationManagerTest.java  # Shared contract tests
│                       ├── AbstractStressIntegrationTest.java   # Shared stress tests
│                       └── hazelcast/
│                           ├── HazelcastReservationManagerTest.java
│                           ├── HazelcastClientReservationManagerTest.java
│                           └── HazelcastStressIntegrationTest.java
```

---

## 11. Dependencies

### 11.1 Runtime Dependencies

```xml
<dependencies>
    <!-- Hazelcast -->
    <dependency>
        <groupId>com.hazelcast</groupId>
        <artifactId>hazelcast</artifactId>
        <version>5.3.6</version>
    </dependency>

    <!-- Micrometer (optional - for metrics) -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-core</artifactId>
        <version>1.12.2</version>
        <optional>true</optional>
    </dependency>
</dependencies>
```

### 11.2 Test Dependencies

```xml
<dependencies>
    <!-- JUnit 5 -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.1</version>
        <scope>test</scope>
    </dependency>

    <!-- AssertJ -->
    <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <version>3.25.1</version>
        <scope>test</scope>
    </dependency>

    <!-- Testcontainers -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
        <version>1.19.3</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>1.19.3</version>
        <scope>test</scope>
    </dependency>

    <!-- Awaitility -->
    <dependency>
        <groupId>org.awaitility</groupId>
        <artifactId>awaitility</artifactId>
        <version>4.2.0</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### 11.3 Build Configuration

```xml
<properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
</properties>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>3.2.3</version>
        </plugin>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-failsafe-plugin</artifactId>
            <version>3.2.3</version>
            <executions>
                <execution>
                    <goals>
                        <goal>integration-test</goal>
                        <goal>verify</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

---

## 12. Roadmap

### Phase 1: Core Implementation
1. Project setup (Maven, dependencies, structure)
2. Core interfaces (`Reservation`, `ReservationManager`)
3. Exception hierarchy
4. `HazelcastReservationManager` implementation
5. `HazelcastReservation` implementation
6. Unit tests with embedded Hazelcast

### Phase 2: Shared Testing & Quality
7. Abstract base test class
8. Integration tests with Testcontainers
9. Concurrency stress tests
10. Edge case handling

### Phase 3: Observability
11. Micrometer metrics integration
12. Metric documentation

### Phase 4: Polish
13. README with usage examples
14. Javadoc documentation
15. Performance benchmarks (JMH)

---

## Appendix A: Usage Examples

### Basic Usage (Hazelcast)

```java
HazelcastInstance hz = HazelcastClient.newHazelcastClient();

// Create manager for the "orders" domain
ReservationManager ordersManager = ReservationManager.hazelcast(hz)
    .domain("orders")
    .leaseTime(Duration.ofMinutes(2))
    .build();

// Get reservation by identifier only
Reservation reservation = ordersManager.getReservation("order-12345");
reservation.lock();
try {
    processOrder("order-12345");
} finally {
    reservation.unlock();
}
```

### Multiple Domains

```java
// Create separate managers for different domains
ReservationManager ordersManager = ReservationManager.hazelcast(hz)
    .domain("orders")
    .build();

ReservationManager usersManager = ReservationManager.hazelcast(hz)
    .domain("users")
    .build();

// Each uses its own isolated Hazelcast map
ordersManager.getReservation("123").lock();  // Uses map "reservations-orders"
usersManager.getReservation("123").lock();   // Uses map "reservations-users"
```

### Try-Lock Pattern

```java
ReservationManager inventoryManager = ReservationManager.hazelcast(hz)
    .domain("inventory")
    .build();

Reservation reservation = inventoryManager.getReservation("sku-ABC123");
if (reservation.tryLock(5, TimeUnit.SECONDS)) {
    try {
        updateInventory("sku-ABC123");
    } finally {
        reservation.unlock();
    }
} else {
    throw new ResourceBusyException("Inventory item is locked");
}
```

### Handling Expiration

```java
ReservationManager reportsManager = ReservationManager.hazelcast(hz)
    .domain("reports")
    .leaseTime(Duration.ofMinutes(10))
    .build();

Reservation reservation = reportsManager.getReservation("daily-report");
reservation.lock();
try {
    generateDailyReport(); // Long-running operation
} finally {
    try {
        reservation.unlock();
    } catch (ReservationExpiredException e) {
        log.error("Report generation took too long, lock expired. " +
                  "Another process may have started generating the same report.");
    }
}
```

## Appendix B: Open Questions / Future Considerations

1. **Lock extension**: Should we support extending lease time while holding?
2. **Lock callbacks**: Event hooks for acquisition/release/expiration?
3. **Spring Integration**: Auto-configuration, `@Reserved` annotation?
4. **Lock querying**: Ability to list all locks in a domain?
5. **Additional backends**: e.g. a JDBC/database-backed implementation via the existing abstractions?
6. **Multi-datacenter**: Support for geo-distributed locking?

---

*End of Design Document*
