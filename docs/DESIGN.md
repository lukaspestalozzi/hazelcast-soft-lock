# Hazelcast Soft-Lock Library - Design Document

> **Version**: 1.0.0-SNAPSHOT
> **Status**: Draft
> **Last Updated**: 2025-12-31

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [API Design](#3-api-design)
4. [Implementation Details](#4-implementation-details)
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

This library provides a **soft-lock** implementation for distributed Java applications using Hazelcast as the synchronization medium. A soft-lock is a distributed lock that **automatically expires** after a configurable lease time (default: 1 minute), preventing deadlocks caused by crashed processes or forgotten unlocks.

### 1.2 Key Features

- Implements `java.util.concurrent.locks.Lock` interface for familiarity
- Automatic lock expiration via configurable lease time
- Lock identity composed of **domain** and **identifier** (both Strings)
- Built on Hazelcast `IMap.lock()` with native lease time support
- Micrometer metrics integration for observability
- Checked exceptions for explicit error handling

### 1.3 Design Decisions Summary

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Hazelcast mechanism | `IMap.lock()` with lease time | Native lease support, simple setup, good performance |
| Lock interface | `SoftLock extends Lock` | Compatibility + additional soft-lock methods |
| `newCondition()` | `UnsupportedOperationException` | Not feasible for distributed locks |
| Key format | Delimiter-based (`domain::identifier`) | Simple, readable, debuggable |
| IMap value | None (lock without entry) | Zero storage overhead |
| Lease time config | Global default on manager | Simplicity with configurability |
| Thread affinity | Strict (Hazelcast native) | Consistency with Lock contract |
| Error handling | Checked exceptions | Explicit failure handling |
| Hazelcast mode | Client only | Production deployment pattern |

---

## 2. Architecture

### 2.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      Application Code                            │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                     SoftLockManager                              │
│  ┌─────────────────┐  ┌─────────────────┐  ┌────────────────┐  │
│  │ Configuration   │  │ Lock Factory    │  │ Metrics        │  │
│  │ - leaseTime     │  │ - getLock()     │  │ - Micrometer   │  │
│  │ - delimiter     │  │ - key building  │  │   integration  │  │
│  │ - mapName       │  │                 │  │                │  │
│  └─────────────────┘  └─────────────────┘  └────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                        SoftLock                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ Implements: java.util.concurrent.locks.Lock             │    │
│  │ Additional: domain, identifier, remainingLeaseTime,     │    │
│  │             forceUnlock                                 │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                   Hazelcast Client                               │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ IMap<String, Void>                                      │    │
│  │ - lock(key, leaseTime, TimeUnit)                        │    │
│  │ - tryLock(key, waitTime, TimeUnit, leaseTime, TimeUnit) │    │
│  │ - unlock(key)                                           │    │
│  │ - forceUnlock(key)                                      │    │
│  │ - isLocked(key)                                         │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                   Hazelcast Cluster                              │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 Component Responsibilities

| Component | Responsibility |
|-----------|----------------|
| `SoftLockManager` | Factory for creating locks, holds configuration and Hazelcast reference |
| `SoftLock` | Individual lock instance, implements Lock interface |
| `SoftLockConfig` | Immutable configuration (lease time, delimiter, map name) |
| `LockKey` | Internal helper for building composite keys |
| `SoftLockMetrics` | Micrometer metrics registration and recording |

### 2.3 Lock Lifecycle

```
┌──────────┐    getLock()    ┌──────────┐
│  START   │────────────────▶│ CREATED  │
└──────────┘                 └──────────┘
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
                          ┌────────────────┐
                          │ IllegalMonitor │
                          │ StateException │
                          └────────────────┘
```

---

## 3. API Design

### 3.1 Core Interfaces

#### 3.1.1 SoftLock Interface

```java
package com.github.softlock;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * A distributed soft-lock that automatically expires after a configured lease time.
 *
 * <p>This lock is identified by a domain and identifier combination, allowing
 * logical grouping of locks (e.g., domain="orders", identifier="12345").</p>
 *
 * <p><b>Important:</b> The {@link #newCondition()} method is not supported for
 * distributed locks and will throw {@link UnsupportedOperationException}.</p>
 *
 * <p><b>Warning:</b> If the lease time expires while the lock is held, calling
 * {@link #unlock()} will throw {@link LockExpiredException}. This indicates that
 * the critical section guarantee may have been violated.</p>
 */
public interface SoftLock extends Lock {

    /**
     * Returns the domain of this lock.
     *
     * @return the domain string, never null
     */
    String getDomain();

    /**
     * Returns the identifier of this lock within its domain.
     *
     * @return the identifier string, never null
     */
    String getIdentifier();

    /**
     * Returns the composite key used for this lock.
     * Format: "{domain}{delimiter}{identifier}"
     *
     * @return the composite key string, never null
     */
    String getLockKey();

    /**
     * Returns the remaining lease time for this lock.
     *
     * @return remaining lease time, or {@link Duration#ZERO} if not locked
     *         or lease has expired
     */
    Duration getRemainingLeaseTime();

    /**
     * Checks if this lock is currently held by any thread.
     *
     * @return true if the lock is held, false otherwise
     */
    boolean isLocked();

    /**
     * Checks if this lock is held by the current thread.
     *
     * @return true if current thread holds the lock, false otherwise
     */
    boolean isHeldByCurrentThread();

    /**
     * Forces the release of this lock regardless of ownership.
     *
     * <p><b>Warning:</b> This is an administrative operation that should only
     * be used for recovery scenarios. It will release the lock even if held
     * by another thread or process.</p>
     */
    void forceUnlock();

    // --- Lock interface methods with soft-lock semantics ---

    /**
     * Acquires the lock, blocking until available.
     * The lock will automatically be released after the configured lease time.
     *
     * @throws LockAcquisitionException if the lock cannot be acquired
     */
    @Override
    void lock() throws LockAcquisitionException;

    /**
     * Acquires the lock unless the current thread is interrupted.
     *
     * @throws InterruptedException if the current thread is interrupted
     * @throws LockAcquisitionException if the lock cannot be acquired
     */
    @Override
    void lockInterruptibly() throws InterruptedException, LockAcquisitionException;

    /**
     * Acquires the lock only if it is free at the time of invocation.
     *
     * @return true if the lock was acquired, false otherwise
     */
    @Override
    boolean tryLock();

    /**
     * Acquires the lock if it becomes available within the given waiting time.
     *
     * @param time the maximum time to wait for the lock
     * @param unit the time unit of the time argument
     * @return true if the lock was acquired, false if the waiting time elapsed
     * @throws InterruptedException if the current thread is interrupted
     */
    @Override
    boolean tryLock(long time, TimeUnit unit) throws InterruptedException;

    /**
     * Releases the lock.
     *
     * @throws LockExpiredException if the lease time has expired before unlock
     * @throws IllegalMonitorStateException if the current thread does not hold the lock
     */
    @Override
    void unlock() throws LockExpiredException;

    /**
     * Not supported for distributed locks.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    default Condition newCondition() {
        throw new UnsupportedOperationException(
            "Conditions are not supported for distributed soft-locks. " +
            "Consider using a distributed coordination service for complex synchronization.");
    }
}
```

#### 3.1.2 SoftLockManager Interface

```java
package com.github.softlock;

import java.io.Closeable;
import java.time.Duration;

/**
 * Factory and manager for {@link SoftLock} instances.
 *
 * <p>A SoftLockManager is bound to a specific Hazelcast client instance and
 * configuration. Multiple managers can coexist, each with their own settings.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * HazelcastInstance hz = HazelcastClient.newHazelcastClient();
 * SoftLockManager manager = SoftLockManager.builder(hz)
 *     .leaseTime(Duration.ofMinutes(1))
 *     .build();
 *
 * SoftLock lock = manager.getLock("orders", "12345");
 * lock.lock();
 * try {
 *     // critical section
 * } finally {
 *     lock.unlock();
 * }
 * }</pre>
 */
public interface SoftLockManager extends Closeable {

    /**
     * Creates a new builder for configuring a SoftLockManager.
     *
     * @param hazelcastInstance the Hazelcast client instance to use
     * @return a new builder instance
     * @throws NullPointerException if hazelcastInstance is null
     */
    static SoftLockManagerBuilder builder(com.hazelcast.core.HazelcastInstance hazelcastInstance) {
        return new SoftLockManagerBuilder(hazelcastInstance);
    }

    /**
     * Obtains a soft-lock for the given domain and identifier.
     *
     * <p>This method always returns a new SoftLock instance, but the underlying
     * distributed lock is shared across all instances with the same domain/identifier.</p>
     *
     * @param domain the lock domain (e.g., "orders", "users", "inventory")
     * @param identifier the identifier within the domain (e.g., order ID, user ID)
     * @return a SoftLock instance for the given domain and identifier
     * @throws IllegalArgumentException if domain or identifier is null, empty,
     *         or contains the configured delimiter
     */
    SoftLock getLock(String domain, String identifier);

    /**
     * Returns the configured lease time for locks created by this manager.
     *
     * @return the lease time duration
     */
    Duration getLeaseTime();

    /**
     * Returns the configured delimiter used for composite keys.
     *
     * @return the delimiter string
     */
    String getDelimiter();

    /**
     * Returns the name of the Hazelcast IMap used for locking.
     *
     * @return the map name
     */
    String getMapName();

    /**
     * Closes this manager and releases associated resources.
     *
     * <p>Note: This does NOT close the underlying Hazelcast instance,
     * nor does it release any currently held locks.</p>
     */
    @Override
    void close();
}
```

### 3.2 Exception Hierarchy

```java
package com.github.softlock;

/**
 * Base exception for all soft-lock related errors.
 */
public class SoftLockException extends Exception {
    public SoftLockException(String message) { super(message); }
    public SoftLockException(String message, Throwable cause) { super(message, cause); }
}

/**
 * Thrown when a lock cannot be acquired.
 */
public class LockAcquisitionException extends SoftLockException {
    private final String domain;
    private final String identifier;

    public LockAcquisitionException(String domain, String identifier, String message) {
        super(message);
        this.domain = domain;
        this.identifier = identifier;
    }

    public LockAcquisitionException(String domain, String identifier, String message, Throwable cause) {
        super(message, cause);
        this.domain = domain;
        this.identifier = identifier;
    }

    public String getDomain() { return domain; }
    public String getIdentifier() { return identifier; }
}

/**
 * Thrown when attempting to unlock a lock whose lease has already expired.
 *
 * <p>This exception indicates a potential violation of the critical section
 * guarantee - another process may have acquired the lock after expiration.</p>
 */
public class LockExpiredException extends SoftLockException {
    private final String domain;
    private final String identifier;

    public LockExpiredException(String domain, String identifier) {
        super(String.format(
            "Lock [%s::%s] lease expired before unlock. Critical section guarantee may be violated.",
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
public class InvalidLockKeyException extends IllegalArgumentException {
    public InvalidLockKeyException(String message) { super(message); }
}
```

### 3.3 Builder Class

```java
package com.github.softlock;

import com.hazelcast.core.HazelcastInstance;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;

/**
 * Builder for creating {@link SoftLockManager} instances.
 */
public final class SoftLockManagerBuilder {

    private final HazelcastInstance hazelcastInstance;
    private Duration leaseTime = Duration.ofMinutes(1);
    private String delimiter = "::";
    private String mapName = "soft-locks";
    private MeterRegistry meterRegistry = null;

    SoftLockManagerBuilder(HazelcastInstance hazelcastInstance) {
        this.hazelcastInstance = Objects.requireNonNull(hazelcastInstance,
            "hazelcastInstance must not be null");
    }

    /**
     * Sets the lease time for locks. Default: 1 minute.
     *
     * @param leaseTime the lease time duration (must be positive)
     * @return this builder
     * @throws IllegalArgumentException if leaseTime is null, zero, or negative
     */
    public SoftLockManagerBuilder leaseTime(Duration leaseTime) {
        Objects.requireNonNull(leaseTime, "leaseTime must not be null");
        if (leaseTime.isZero() || leaseTime.isNegative()) {
            throw new IllegalArgumentException("leaseTime must be positive");
        }
        this.leaseTime = leaseTime;
        return this;
    }

    /**
     * Sets the delimiter for composite keys. Default: "::"
     *
     * @param delimiter the delimiter string (must not be null or empty)
     * @return this builder
     */
    public SoftLockManagerBuilder delimiter(String delimiter) {
        Objects.requireNonNull(delimiter, "delimiter must not be null");
        if (delimiter.isEmpty()) {
            throw new IllegalArgumentException("delimiter must not be empty");
        }
        this.delimiter = delimiter;
        return this;
    }

    /**
     * Sets the Hazelcast IMap name for storing locks. Default: "soft-locks"
     *
     * @param mapName the map name (must not be null or empty)
     * @return this builder
     */
    public SoftLockManagerBuilder mapName(String mapName) {
        Objects.requireNonNull(mapName, "mapName must not be null");
        if (mapName.isEmpty()) {
            throw new IllegalArgumentException("mapName must not be empty");
        }
        this.mapName = mapName;
        return this;
    }

    /**
     * Sets the Micrometer registry for metrics. Default: none (metrics disabled)
     *
     * @param meterRegistry the meter registry, or null to disable metrics
     * @return this builder
     */
    public SoftLockManagerBuilder meterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        return this;
    }

    /**
     * Builds the SoftLockManager with the configured settings.
     *
     * @return a new SoftLockManager instance
     */
    public SoftLockManager build() {
        return new DefaultSoftLockManager(
            hazelcastInstance,
            leaseTime,
            delimiter,
            mapName,
            meterRegistry
        );
    }
}
```

---

## 4. Implementation Details

### 4.1 Lock Key Construction

```java
/**
 * Internal utility for building and validating lock keys.
 */
final class LockKeyBuilder {

    private final String delimiter;

    LockKeyBuilder(String delimiter) {
        this.delimiter = delimiter;
    }

    String buildKey(String domain, String identifier) {
        validate(domain, "domain");
        validate(identifier, "identifier");
        return domain + delimiter + identifier;
    }

    private void validate(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new InvalidLockKeyException(name + " must not be null or empty");
        }
        if (value.contains(delimiter)) {
            throw new InvalidLockKeyException(
                name + " must not contain the delimiter '" + delimiter + "': " + value);
        }
    }
}
```

### 4.2 IMap Usage Pattern

Since we're using `IMap.lock()` without storing values:

```java
// Acquire lock with lease time
IMap<String, Object> lockMap = hazelcastInstance.getMap(mapName);
String lockKey = domain + "::" + identifier;

// Blocking acquire with lease
lockMap.lock(lockKey, leaseTime.toMillis(), TimeUnit.MILLISECONDS);

// Non-blocking acquire with lease
boolean acquired = lockMap.tryLock(
    lockKey,
    waitTime, waitTimeUnit,           // how long to wait
    leaseTime.toMillis(), TimeUnit.MILLISECONDS  // auto-release after
);

// Release
try {
    lockMap.unlock(lockKey);
} catch (IllegalMonitorStateException e) {
    // Lock expired or not held by current thread
    throw new LockExpiredException(domain, identifier);
}

// Check status
boolean locked = lockMap.isLocked(lockKey);
```

### 4.3 Handling Lock Expiration

The critical challenge: detecting when a lock has expired before `unlock()`:

```java
@Override
public void unlock() throws LockExpiredException {
    try {
        lockMap.unlock(lockKey);
        metrics.recordUnlock(domain, identifier, true);
    } catch (IllegalMonitorStateException e) {
        // This happens when:
        // 1. Current thread doesn't own the lock (programming error)
        // 2. Lease expired (soft-lock did its job, but critical section may be violated)
        metrics.recordUnlock(domain, identifier, false);

        if (wasHeldByCurrentThread()) {
            // We held it, but lease expired
            throw new LockExpiredException(domain, identifier);
        } else {
            // Programming error - didn't own the lock
            throw e;
        }
    }
}
```

### 4.4 Thread Safety

The implementation must be thread-safe:

- `SoftLockManager`: Immutable after construction, thread-safe
- `SoftLock`: Each instance wraps a specific key; thread-safe via Hazelcast's guarantees
- `LockKeyBuilder`: Immutable, thread-safe

---

## 5. Configuration

### 5.1 Configuration Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `leaseTime` | `Duration` | 1 minute | Time after which lock auto-releases |
| `delimiter` | `String` | `::` | Separator between domain and identifier |
| `mapName` | `String` | `soft-locks` | Hazelcast IMap name |
| `meterRegistry` | `MeterRegistry` | `null` | Micrometer registry (null = no metrics) |

### 5.2 Hazelcast Client Configuration

The library expects a pre-configured `HazelcastInstance`. Example client setup:

```java
ClientConfig config = new ClientConfig();
config.setClusterName("my-cluster");
config.getNetworkConfig().addAddress("hazelcast-node1:5701", "hazelcast-node2:5701");

// Connection retry
config.getConnectionStrategyConfig()
    .getConnectionRetryConfig()
    .setClusterConnectTimeoutMillis(30000);

HazelcastInstance hz = HazelcastClient.newHazelcastClient(config);
```

---

## 6. Error Handling

### 6.1 Exception Strategy

All public API methods use **checked exceptions** to force explicit handling:

| Method | Throws | Reason |
|--------|--------|--------|
| `lock()` | `LockAcquisitionException` | Network/cluster issues |
| `lockInterruptibly()` | `InterruptedException`, `LockAcquisitionException` | Interruption, network issues |
| `tryLock()` | (none - returns boolean) | Non-blocking, failure = false |
| `tryLock(time, unit)` | `InterruptedException` | Timeout = false, interruption = exception |
| `unlock()` | `LockExpiredException` | Lease expired before unlock |
| `newCondition()` | `UnsupportedOperationException` | Not supported |

### 6.2 Recovery Scenarios

| Scenario | Behavior | Recovery |
|----------|----------|----------|
| Network partition during lock hold | Lock may expire; other node may acquire | `LockExpiredException` on unlock |
| Client crash while holding lock | Hazelcast releases lock (member death) | Automatic |
| Lease expires during critical section | Another thread may acquire lock | `LockExpiredException` warns of violation |
| Hazelcast cluster unavailable | `LockAcquisitionException` | Retry with backoff |

---

## 7. Observability

### 7.1 Micrometer Metrics

| Metric Name | Type | Tags | Description |
|-------------|------|------|-------------|
| `softlock.acquire` | Timer | `domain`, `result` | Lock acquisition time and outcome |
| `softlock.acquire.attempts` | Counter | `domain`, `result` | Acquisition attempts (success/failure) |
| `softlock.held.time` | Timer | `domain` | Duration lock was held |
| `softlock.expired` | Counter | `domain` | Locks that expired before unlock |
| `softlock.active` | Gauge | `domain` | Currently held locks (approximate) |

### 7.2 Metric Tags

- `domain`: The lock domain (for grouping/filtering)
- `result`: `acquired`, `timeout`, `interrupted`, `error`

### 7.3 Example Metrics Registration

```java
class SoftLockMetrics {
    private final MeterRegistry registry;
    private final Timer acquireTimer;
    private final Counter expiredCounter;

    SoftLockMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.acquireTimer = Timer.builder("softlock.acquire")
            .description("Time to acquire soft-lock")
            .register(registry);
        this.expiredCounter = Counter.builder("softlock.expired")
            .description("Locks expired before unlock")
            .register(registry);
    }

    void recordAcquisition(String domain, Duration elapsed, boolean success) {
        Timer.builder("softlock.acquire")
            .tag("domain", domain)
            .tag("result", success ? "acquired" : "failed")
            .register(registry)
            .record(elapsed);
    }

    void recordExpiration(String domain) {
        Counter.builder("softlock.expired")
            .tag("domain", domain)
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
                    │   E2E Tests     │  ← Manual/staged (real cluster)
                    │   (Few)         │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │  Integration    │  ← Testcontainers (Hazelcast Docker)
                    │  Tests          │
                    │  (Medium)       │
                    └────────┬────────┘
                             │
          ┌──────────────────▼──────────────────┐
          │           Unit Tests                 │  ← Embedded Hazelcast
          │           (Many)                     │
          └──────────────────────────────────────┘
```

### 8.2 Unit Tests (Embedded Hazelcast)

Fast, isolated tests using embedded Hazelcast:

```java
class SoftLockUnitTest {

    private static HazelcastInstance hz;
    private SoftLockManager manager;

    @BeforeAll
    static void setupCluster() {
        Config config = new Config();
        config.setClusterName("test-" + UUID.randomUUID());
        hz = Hazelcast.newHazelcastInstance(config);
    }

    @BeforeEach
    void setup() {
        manager = SoftLockManager.builder(hz)
            .leaseTime(Duration.ofSeconds(5))
            .mapName("test-locks-" + UUID.randomUUID())
            .build();
    }

    @Test
    void shouldAcquireAndReleaseLock() throws Exception {
        SoftLock lock = manager.getLock("orders", "123");

        lock.lock();
        assertThat(lock.isHeldByCurrentThread()).isTrue();

        lock.unlock();
        assertThat(lock.isLocked()).isFalse();
    }

    @Test
    void shouldExpireAfterLeaseTime() throws Exception {
        SoftLock lock = manager.getLock("orders", "456");
        lock.lock();

        // Wait for lease to expire
        Thread.sleep(6000);

        assertThat(lock.isLocked()).isFalse();
        assertThatThrownBy(lock::unlock)
            .isInstanceOf(LockExpiredException.class);
    }

    @Test
    void shouldBlockConcurrentAcquisition() throws Exception {
        SoftLock lock = manager.getLock("orders", "789");
        lock.lock();

        CompletableFuture<Boolean> otherThread = CompletableFuture.supplyAsync(() -> {
            SoftLock sameLock = manager.getLock("orders", "789");
            return sameLock.tryLock();
        });

        assertThat(otherThread.get()).isFalse();
        lock.unlock();
    }
}
```

### 8.3 Integration Tests (Testcontainers)

Realistic multi-node testing:

```java
@Testcontainers
class SoftLockIntegrationTest {

    @Container
    static HazelcastContainer hazelcast = new HazelcastContainer("hazelcast/hazelcast:5.3");

    private HazelcastInstance client;
    private SoftLockManager manager;

    @BeforeEach
    void setup() {
        ClientConfig config = new ClientConfig();
        config.getNetworkConfig().addAddress(hazelcast.getHost() + ":" + hazelcast.getFirstMappedPort());
        client = HazelcastClient.newHazelcastClient(config);
        manager = SoftLockManager.builder(client).build();
    }

    @Test
    void shouldHandleMultipleClientsCompeting() throws Exception {
        // Create second client
        HazelcastInstance client2 = HazelcastClient.newHazelcastClient(/*...*/);
        SoftLockManager manager2 = SoftLockManager.builder(client2).build();

        SoftLock lock1 = manager.getLock("test", "shared");
        SoftLock lock2 = manager2.getLock("test", "shared");

        lock1.lock();
        assertThat(lock2.tryLock()).isFalse();

        lock1.unlock();
        assertThat(lock2.tryLock()).isTrue();
        lock2.unlock();
    }
}
```

### 8.4 Test Categories

| Category | Framework | Purpose | Examples |
|----------|-----------|---------|----------|
| Unit | JUnit 5 + Embedded HZ | Core logic, fast feedback | Lock/unlock, expiration, reentrancy |
| Integration | Testcontainers | Multi-client scenarios | Competing clients, failover |
| Concurrency | JCStress / custom | Thread safety | Race conditions, visibility |
| Performance | JMH | Benchmarks | Throughput, latency |

---

## 9. Performance Considerations

### 9.1 Lock Operation Costs

| Operation | Expected Latency | Notes |
|-----------|------------------|-------|
| `lock()` (uncontended) | < 1ms (local cluster) | Network RTT to partition owner |
| `tryLock()` (uncontended) | < 1ms | Same as lock() |
| `unlock()` | < 1ms | Network RTT |
| `isLocked()` | < 1ms | Read operation |

### 9.2 Optimization Guidelines

1. **Lock Granularity**: Use fine-grained identifiers to reduce contention
   - Good: `("orders", "order-12345")`
   - Bad: `("orders", "all")`

2. **Lease Time Tuning**: Balance between:
   - Too short: Frequent expirations, potential violations
   - Too long: Resources blocked longer on failures

3. **Map Partitioning**: Hazelcast distributes locks across partitions
   - Diverse domains/identifiers = better distribution

4. **Client Proximity**: Deploy clients close to Hazelcast nodes

### 9.3 Benchmarking Plan

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class SoftLockBenchmark {

    @Benchmark
    public void lockUnlockCycle(BenchmarkState state) throws Exception {
        SoftLock lock = state.manager.getLock("bench", "key-" + ThreadLocalRandom.current().nextInt(1000));
        lock.lock();
        lock.unlock();
    }

    @Benchmark
    public void tryLockContended(BenchmarkState state) {
        SoftLock lock = state.manager.getLock("bench", "contended");
        if (lock.tryLock()) {
            lock.unlock();
        }
    }
}
```

---

## 10. Project Structure

```
hazelcast-soft-lock/
├── pom.xml
├── README.md
├── docs/
│   └── DESIGN.md                          # This document
├── src/
│   ├── main/
│   │   └── java/
│   │       └── com/
│   │           └── github/
│   │               └── softlock/
│   │                   ├── SoftLock.java                # Main interface
│   │                   ├── SoftLockManager.java         # Factory interface
│   │                   ├── SoftLockManagerBuilder.java  # Builder
│   │                   ├── SoftLockException.java       # Base exception
│   │                   ├── LockAcquisitionException.java
│   │                   ├── LockExpiredException.java
│   │                   ├── InvalidLockKeyException.java
│   │                   └── internal/
│   │                       ├── DefaultSoftLockManager.java
│   │                       ├── DefaultSoftLock.java
│   │                       ├── LockKeyBuilder.java
│   │                       └── SoftLockMetrics.java
│   └── test/
│       └── java/
│           └── com/
│               └── github/
│                   └── softlock/
│                       ├── SoftLockTest.java            # Unit tests
│                       ├── SoftLockManagerTest.java
│                       ├── LockKeyBuilderTest.java
│                       ├── integration/
│                       │   └── SoftLockIntegrationTest.java
│                       └── benchmark/
│                           └── SoftLockBenchmark.java
```

---

## 11. Dependencies

### 11.1 Runtime Dependencies

```xml
<dependencies>
    <!-- Hazelcast Client -->
    <dependency>
        <groupId>com.hazelcast</groupId>
        <artifactId>hazelcast</artifactId>
        <version>5.3.6</version>
    </dependency>

    <!-- Micrometer (optional, for metrics) -->
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

    <!-- Awaitility (for async assertions) -->
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
2. Core interfaces (`SoftLock`, `SoftLockManager`)
3. Exception hierarchy
4. `DefaultSoftLockManager` implementation
5. `DefaultSoftLock` implementation
6. Unit tests with embedded Hazelcast

### Phase 2: Quality & Testing
7. Integration tests with Testcontainers
8. Concurrency tests
9. Edge case handling (expiration, interruption)
10. Javadoc documentation

### Phase 3: Observability
11. Micrometer metrics integration
12. Metric documentation

### Phase 4: Polish
13. README with usage examples
14. Performance benchmarks (JMH)
15. CI/CD pipeline setup (optional)

---

## Appendix A: Usage Examples

### Basic Usage

```java
// Setup
HazelcastInstance hz = HazelcastClient.newHazelcastClient();
SoftLockManager lockManager = SoftLockManager.builder(hz)
    .leaseTime(Duration.ofMinutes(2))
    .build();

// Acquire and use lock
SoftLock lock = lockManager.getLock("orders", "order-12345");
lock.lock();
try {
    // Process order - guaranteed exclusive for up to 2 minutes
    processOrder("order-12345");
} finally {
    lock.unlock();
}
```

### Try-Lock Pattern

```java
SoftLock lock = lockManager.getLock("inventory", "sku-ABC123");
if (lock.tryLock(5, TimeUnit.SECONDS)) {
    try {
        updateInventory("sku-ABC123");
    } finally {
        lock.unlock();
    }
} else {
    // Could not acquire lock within 5 seconds
    throw new ResourceBusyException("Inventory item is locked");
}
```

### Handling Expiration

```java
SoftLock lock = lockManager.getLock("reports", "daily-report");
lock.lock();
try {
    generateDailyReport(); // Long-running operation
} catch (LockExpiredException e) {
    // Lock expired during processing!
    log.error("Report generation took too long, lock expired. " +
              "Another process may have started generating the same report.");
    // Consider: abort, retry with longer lease, or alert
} finally {
    try {
        lock.unlock();
    } catch (LockExpiredException ignored) {
        // Already logged above
    }
}
```

---

## Appendix B: Open Questions / Future Considerations

1. **Lock extension**: Should we support extending lease time while holding the lock?
2. **Lock callbacks**: Event hooks for acquisition/release/expiration?
3. **Distributed Condition**: Alternative coordination patterns?
4. **Spring Integration**: Auto-configuration, `@SoftLocked` annotation?
5. **Lock querying**: Ability to list all locks in a domain?

---

*End of Design Document*
