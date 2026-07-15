# Reservation Lock Library - Design Document

> **Version**: 1.0.0-SNAPSHOT
> **Status**: Living document — describes design intent and decisions.
> For exact signatures and behavior, the source and its javadoc are authoritative.

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
12. [Open Questions / Future Considerations](#12-open-questions--future-considerations)

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
- **Domain isolation**: separate IMaps per domain
- Micrometer metrics integration for observability (optional dependency)
- Reentrant locking support, tracked per thread across Reservation instances
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
| Hazelcast value | String with debug info | Debuggability with low overhead; opt-out via `debugValues(false)` for hot locks |
| Micrometer coupling | Optional dependency, typed builder method | Standard optional-dependency pattern; no reflection; pinned by a filtered-classloader test |
| Metrics safety | Guarding decorator, meters cached | A throwing registry must never corrupt lock state; fixed tag space needs no per-op meter lookup |
| Stale-hold detection | Local clock with safety margin | Mis-detection must fail loud (leaked hold until lease expiry), never silent (released while believed held) — see §4.4 |
| Unlock failure | `ReservationExpiredException` vs `ReservationReleaseException` | Distinguish "ownership lost" from "outcome unknown, retryable" |
| Consistency model | Hazelcast IMap locks (AP) | Lease support is the core feature; CP `FencedLock` has no lease — accepted split-brain caveat, see §4.6 |
| Hazelcast version | 5.4.x line | 5.5+ dropped the Apache-2.0 open source edition, conflicting with this library's license |

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
│         Additional: identifier, remainingLeaseTime, forceUnlock          │
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
| `AbstractReservationManagerBuilder` | Shared builder configuration (domain, leaseTime, meterRegistry) |
| `HazelcastReservationManager` | Hazelcast-backed implementation (uses domain-specific IMap) |
| `HoldTracker` (internal) | Per-thread hold state, shared per manager, enables reentrancy across instances |
| `ReservationMetrics` (internal) | Metrics abstraction; Micrometer-backed or no-op |

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
                             │ ReservationExpired │
                             │ Exception       │
                             └─────────────────┘
```

---

## 3. API Design

The public API lives in `com.github.reservation` (see the source javadoc for full contracts):

- **`Reservation extends Lock`** (`Reservation.java`) — adds `getIdentifier()`, `getRemainingLeaseTime()` (a per-thread, local estimate), `isLocked()` (held by *anyone*), `isHeldByCurrentThread()` (held by *me*, with a conservative safety margin near lease end), and the administrative `forceUnlock()`. `newCondition()` throws `UnsupportedOperationException`. `unlock()` after ownership loss throws `ReservationExpiredException`; unlock failing on infrastructure throws `ReservationReleaseException` (hold kept, retryable); unlocking without holding throws `IllegalMonitorStateException`.
- **`ReservationManager extends Closeable`** (`ReservationManager.java`) — the factory bound to one backend + one domain. Static entry point: `ReservationManager.hazelcast(hazelcastInstance)` returns the Hazelcast builder. This couples the core interface to the Hazelcast types at compile time — a deliberate convenience while there is a single backend; if a second backend lands and consumers need Hazelcast-free classpaths, discovery moves to the builders. `getReservation(identifier)` returns a new instance each call, but the underlying distributed lock (and per-thread hold state) is shared for the same identifier. `close()` releases manager resources only — never the injected Hazelcast instance or held locks.
- **Exception hierarchy** — `ReservationException extends RuntimeException` (the `Lock` interface cannot declare checked exceptions), with `ReservationAcquisitionException` (infrastructure failure during acquire), `ReservationExpiredException` (ownership lost before unlock — usually lease expiry, but a force-release or cluster event is indistinguishable; carries domain + identifier), and `ReservationReleaseException` (unlock failed for infrastructure reasons; the outcome is unknown and the local hold is kept so unlock can be retried — lease expiry is the backstop). `InvalidReservationKeyException` deliberately extends `IllegalArgumentException` instead: an invalid identifier is a caller programming error, not a reservation runtime condition (so `catch (ReservationException)` does not catch it).
- **Builders** — `AbstractReservationManagerBuilder` holds shared configuration: `domain(String)` (required), `leaseTime(Duration)` (default 1 minute, must be positive and expressible in milliseconds), `meterRegistry(MeterRegistry)` (optional; requires Micrometer on the classpath when called). `HazelcastReservationManagerBuilder` adds `mapPrefix(String)` and `debugValues(boolean)`. `build()` throws `IllegalStateException` if domain is unset.

Adding a backend means: implement `Reservation` + `ReservationManager`, extend the abstract builder, expose a static factory on `ReservationManager`, reuse `HoldTracker`/`ReservationMetrics` from `internal/`, and pass the shared test suites (§8).

---

## 4. Hazelcast Implementation

### 4.1 Overview

`HazelcastReservation` maps the Lock contract onto `IMap` pessimistic locking with native lease support (`IMap.lock(key, lease, unit)` / `tryLock(key, wait, unit, lease, unit)`). Each domain uses a separate IMap named `{mapPrefix}-{domain}`; the map key is the plain identifier.

### 4.2 Debug Value

For operational debuggability, a value of the form `holder={thread}@{host},acquired={instant}` is stored in the map entry (with TTL = lease time) after each acquisition, and removed on final unlock. All debug-value writes/removals are **best-effort**: a failure must never turn a successful acquisition into an apparent failure (which would leak the lock until lease expiry) or block unlock. Removal uses `tryRemove` with zero timeout so an expired holder never blocks on the entry lock of a new holder.

The debug value costs one extra map operation per acquisition and per final unlock; `debugValues(false)` on the builder disables it for hot locks.

### 4.3 Interrupt Handling (deliberate, not incidental)

Hazelcast's locking API forces two workarounds that must be preserved:

- `IMap.lock()` is not interruptible, but `Lock.lock()` must keep waiting through interrupts. `lock()` therefore retries in a loop, records that an interrupt happened, clears the flag before retrying (a set flag would make the next attempt fail immediately and spin), and re-asserts it before returning.
- The **client-side** proxy surfaces interrupts as `HazelcastException(InterruptedException)` instead of throwing `InterruptedException` like the member-side proxy. All acquisition paths therefore walk the cause chain (`causedByInterrupt`) and translate. `lockInterruptibly()` polls `tryLock` in slices, checking the interrupt flag between attempts; the slice starts at 100 ms (fast interrupt response) and backs off exponentially to a 1 s cap, limiting per-waiter network chatter on the client topology during long waits.

### 4.4 Reentrancy and Hold Tracking

Hazelcast locks are reentrant per thread, but hold state must also work across different `Reservation` instances from the same manager. The per-manager `HoldTracker` keeps a thread-local map `identifier → Hold{count, acquiredAt}`:

- `unlock()` consults the tracker to distinguish "never held" (`IllegalMonitorStateException`) from "ownership lost" (`ReservationExpiredException`, thrown when the cluster no longer recognizes the hold we tracked).
- On re-acquisition after a lapsed lease, the stale count is reset — carrying it forward would let later unlocks release more than was acquired.
- **Safety margin (load-bearing):** whether the previous lease lapsed is judged by the local clock, but the cluster measures the lease from an earlier instant (the server granted the lock before its response arrived), so the two clocks can never agree exactly. The comparison is therefore biased by a margin (`min(500 ms, lease/5)`) toward *over*-detecting expiry. Rationale: over-detection merely leaks one reentrant server-side hold until lease expiry (loud, self-healing); under-detection would let a single `unlock()` release a freshly granted lock while the caller still believes it holds another count — silent loss of mutual exclusion. The contract test `reacquireNearLeaseEndMustNeverSilentlyReleaseTheLock` pins this failure-mode choice.
- `getRemainingLeaseTime()` derives from the tracked `acquiredAt` and the configured lease — a local estimate, not a cluster query.
- **Lifecycle:** the per-thread map is created lazily on first hold and dropped as soon as it empties, so pooled threads retain no empty state. A hold whose lease expired without unlock stays until the same thread touches the same identifier again (bounded; unreachable from the manager by design). Cross-thread operations (`forceUnlock`) cannot clean other threads' local state — it goes stale and surfaces on their next `unlock()`.

### 4.5 Thread Safety

`HazelcastReservationManager` is immutable after construction. `HazelcastReservation` is thread-safe via Hazelcast's guarantees plus the thread-local hold state; ownership is strictly per-thread.

### 4.6 Guarantees and Limitations (read this)

This is a **soft lock** — advisory, best-effort mutual exclusion with lease expiry as the safety net. Two scenarios can end exclusivity without the holder noticing immediately:

1. **Lease expiry mid-critical-section.** Detected lazily, at `unlock()` time, as `ReservationExpiredException`.
2. **Split-brain.** `IMap` locks are partition-based (AP), not consensus-based: during a network partition, *both* sides can grant the same reservation simultaneously, and no exception ever surfaces this. Hazelcast's CP Subsystem (`FencedLock`) closes this gap but offers no lease-time auto-expiry, which is this library's core feature — the AP trade-off is accepted deliberately. The stress suite's mutual-exclusion proofs run against a healthy cluster and cannot detect partition behavior.

Consequently: reservations must not be the sole guard for non-idempotent, unfenced side effects. Suitable uses are deduplication, best-effort serialization, and efficiency locks — anywhere the protected operation is idempotent, fenced (e.g. datastore version check), or a rare double-execution is tolerable.

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

See `README.md` for client setup and usage examples.

---

## 6. Error Handling

### 6.1 Exception Strategy

The `ReservationException` hierarchy extends `RuntimeException` because the
`java.util.concurrent.locks.Lock` interface does not allow checked exceptions:

| Method | Throws | Reason |
|--------|--------|--------|
| `lock()` | `ReservationAcquisitionException` | Network/backend issues |
| `lockInterruptibly()` | `InterruptedException`, `ReservationAcquisitionException` | Interruption, infrastructure issues |
| `tryLock()` | `ReservationAcquisitionException` | Held by another = false; backend error = exception; interrupt = false with flag restored |
| `tryLock(time, unit)` | `InterruptedException`, `ReservationAcquisitionException` | Timeout = false; interruption or backend error = exception |
| `unlock()` | `ReservationExpiredException`, `ReservationReleaseException`, `IllegalMonitorStateException` | Ownership lost / infra failure (retryable, hold kept) / not held |
| `newCondition()` | `UnsupportedOperationException` | Not supported |

### 6.2 Recovery Scenarios

| Scenario | Behavior |
|----------|----------|
| Network partition | **Mutual exclusion may be violated**: both sides can grant the same reservation (see §4.6); after healing, the losing holder surfaces as `ReservationExpiredException` on unlock at best |
| Client/Process crash | Hazelcast releases (member death) |
| Lease expires during critical section | `ReservationExpiredException` on unlock |
| Backend unavailable during acquire | `ReservationAcquisitionException` |
| Backend unavailable during unlock | `ReservationReleaseException`; hold kept, unlock retryable, lease expiry as backstop |
| Force-release by an admin | Holder's next unlock throws `ReservationExpiredException` (indistinguishable from expiry) |

---

## 7. Observability

### 7.1 Micrometer Metrics

Recorded when a `MeterRegistry` is configured on the builder (see `internal/MicrometerReservationMetrics.java`); otherwise a no-op implementation is used. Each metrics instance is scoped to one manager, so `backend` and `domain` tags are fixed at creation; meters are created once and cached (the tag space is tiny and fixed).

Metrics run **after** lock state has already changed, so the Micrometer implementation is wrapped in a guarding decorator (`GuardedReservationMetrics`): a throwing registry is logged and ignored — it must never make a successful acquisition look failed (which would leak the lock until lease expiry) or abort unlock bookkeeping.

| Metric Name | Type | Tags | Description |
|-------------|------|------|-------------|
| `reservation.acquire` | Timer | `domain`, `backend`, `result` | Acquisition time and outcome (attempt counts are derivable from per-result counts) |
| `reservation.held.time` | Timer | `domain`, `backend` | Duration reservation was held |
| `reservation.expired` | Counter | `domain`, `backend` | Ownership lost before unlock (lease expiry or force-release) |

### 7.2 Metric Tags

- `domain`: The reservation domain (for grouping/filtering)
- `backend`: `hazelcast`
- `result`: `acquired`, `timeout`, `unavailable`, `interrupted`, `error`

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

### 8.2 Shared Test Suites

All implementations run the same tests via abstract base classes; a backend passes by subclassing and implementing `createManager(domain, leaseTime)` / `cleanup()`:

- **`AbstractReservationManagerTest`** — the functional contract: lock/unlock, expiration, reentrancy (including across instances and unlock via a different instance), tryLock variants, forceUnlock, interruptibility, identifier validation, `newCondition` rejection, identifier isolation.
- **`AbstractStressIntegrationTest`** — 21 stress properties at production-level concurrency: mutual-exclusion proofs (occupancy counter, non-atomic shared counter, list serialization), throughput floors, high contention, thundering herd, CyclicBarrier simultaneous hits, expiry races, domain isolation under load, interrupt storms, burst-and-recover, fairness. Load parameters (`highThreadCount()` etc.) are overridable for slower environments.

### 8.3 Concrete Suites

- `HazelcastReservationManagerTest` — contract tests against an embedded member; plus Hazelcast-specific tests (map naming, debug value + opt-out, domain isolation, builder validation, metrics recording, backend-error mapping for acquire and release).
- `HazelcastClientReservationManagerTest` — the same contract via a Hazelcast **client** connected to an embedded member: client proxies behave differently in places (e.g. interrupt wrapping, §4.3), so the contract must hold for both topologies.
- `HazelcastMultiMemberReservationTest` — lock visibility and domain isolation across two clustered embedded members (a single-member suite cannot detect member-local locking bugs).
- `MicrometerOptionalityTest` — runs a full lock/unlock scenario inside a classloader that refuses to load `io.micrometer.*`, pinning the optional-dependency claim (§1.3).
- `HazelcastStressIntegrationTest` — stress suite against a real Hazelcast container (Testcontainers); runs only with `-Pintegration-tests` (in CI: on pull requests and default-branch pushes).

---

## 9. Performance Considerations

- Uncontended `lock`/`tryLock`/`unlock`/`isLocked` are single Hazelcast operations (< 1 ms typical, network-bound for clients).
- **Client proximity**: deploy clients close to Hazelcast nodes.
- **Lock granularity**: use fine-grained identifiers; diverse keys distribute across partitions.
- Contended waiting is delegated to Hazelcast (no client-side polling except `lockInterruptibly`'s 100 ms interrupt-check slices).

---

## 10. Project Structure

```
reservation-lock/
├── pom.xml
├── README.md
├── docs/DESIGN.md                              # This document
├── scripts/                                    # Dev-environment proxy helpers (not shipped)
└── src/
    ├── main/java/com/github/reservation/
    │   ├── Reservation.java                    # Main interface
    │   ├── ReservationManager.java             # Factory interface
    │   ├── AbstractReservationManagerBuilder.java
    │   ├── ReservationException.java           # Base (unchecked)
    │   ├── ReservationAcquisitionException.java
    │   ├── ReservationExpiredException.java
    │   ├── ReservationReleaseException.java
    │   ├── InvalidReservationKeyException.java
    │   ├── hazelcast/
    │   │   ├── HazelcastReservationManager.java
    │   │   ├── HazelcastReservation.java
    │   │   └── HazelcastReservationManagerBuilder.java
    │   └── internal/                           # Not public API
    │       ├── HoldTracker.java
    │       ├── ReservationMetrics.java
    │       ├── GuardedReservationMetrics.java
    │       ├── MicrometerReservationMetrics.java
    │       └── NoOpReservationMetrics.java
    └── test/java/com/github/reservation/
        ├── AbstractReservationManagerTest.java  # Shared contract tests
        ├── AbstractStressIntegrationTest.java   # Shared stress tests
        ├── MicrometerOptionalityTest.java       # Micrometer-free classpath proof
        ├── MicrometerFreeScenario.java
        └── hazelcast/
            ├── HazelcastReservationManagerTest.java
            ├── HazelcastClientReservationManagerTest.java
            ├── HazelcastMultiMemberReservationTest.java
            └── HazelcastStressIntegrationTest.java
```

---

## 11. Dependencies

Declared in `pom.xml` (authoritative). Summary:

- **Runtime**: `com.hazelcast:hazelcast` (required; pinned to the 5.4.x line — 5.5+ is no longer Apache-2.0 licensed), `io.micrometer:micrometer-core` (optional — only needed when `meterRegistry(...)` is used; verified by `MicrometerOptionalityTest`), `org.slf4j:slf4j-api`.
- **Test**: JUnit 5, AssertJ, Awaitility, Testcontainers (core + junit-jupiter), Logback.
- **Build**: Java 21 (`maven.compiler.release`), Enforcer (Java 21+, Maven 3.6.3+), Surefire for `*Test`, Failsafe for `*IntegrationTest` behind the `integration-tests` profile; both with fork-timeout safety nets so a hung test JVM fails the build instead of stalling it.

---

## 12. Open Questions / Future Considerations

1. **Lock extension**: Should we support extending lease time while holding? (Related: `isHeldByCurrentThread()` now exists for callers to check before attempting.)
2. **Lock callbacks**: Event hooks for acquisition/release/expiration?
3. **Spring Integration**: Auto-configuration, `@Reserved` annotation?
4. **Lock querying**: Ability to list all locks in a domain?
5. **Additional backends**: e.g. a JDBC/database-backed implementation via the existing abstractions?
6. **Multi-datacenter**: Support for geo-distributed locking?

---

*End of Design Document*
