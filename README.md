# Reservation Lock Library

A distributed soft-lock library for Java implementing `java.util.concurrent.locks.Lock` with automatic lease expiration. Prevents deadlocks from crashed processes or forgotten unlocks.

## Features

- **Implements `java.util.concurrent.locks.Lock`** - Familiar API for Java developers
- **Automatic expiration** - Locks auto-release after configurable lease time (default: 1 minute)
- **Hazelcast backend** - Built on IMap.lock with lease support; the core abstractions allow additional backends in the future
- **Reentrant locking** - Same thread can acquire the same lock multiple times
- **Micrometer metrics** - Built-in observability support
- **Domain isolation** - Each manager handles one domain (e.g., "orders", "users")

## Installation

```xml
<dependency>
    <groupId>com.github.reservation</groupId>
    <artifactId>reservation-lock</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Quick Start

```java
HazelcastInstance hz = HazelcastClient.newHazelcastClient();

// Create a manager for the "orders" domain
ReservationManager ordersManager = ReservationManager.hazelcast(hz)
    .domain("orders")
    .leaseTime(Duration.ofMinutes(2))
    .build();

// Get reservation by identifier only - domain comes from manager
Reservation reservation = ordersManager.getReservation("order-12345");
reservation.lock();
try {
    // Critical section - only one process can execute this
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
Reservation reservation = inventoryManager.getReservation("sku-ABC123");
if (reservation.tryLock(5, TimeUnit.SECONDS)) {
    try {
        updateInventory("sku-ABC123");
    } finally {
        reservation.unlock();
    }
} else {
    throw new ResourceBusyException("Inventory item is locked by another process");
}
```

### Handling Expiration

```java
reservation.lock();
try {
    longRunningOperation();
} finally {
    try {
        reservation.unlock();
    } catch (ReservationExpiredException e) {
        log.error("Lock expired during operation - another process may have acquired it");
        // Handle potential data inconsistency
    }
}
```

## Configuration

### Common Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `domain` | `String` | **required** | Domain for this manager |
| `leaseTime` | `Duration` | 1 minute | Auto-release time |
| `meterRegistry` | `MeterRegistry` | null | Micrometer metrics |

### Hazelcast-Specific

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `mapPrefix` | `String` | `reservations` | Prefix for IMap name (actual name: `{prefix}-{domain}`) |

## Micrometer Metrics

When a `MeterRegistry` is provided, the following metrics are recorded:

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `reservation.acquire` | Timer | domain, backend, result | Acquisition time |
| `reservation.acquire.attempts` | Counter | domain, backend, result | Acquisition attempts |
| `reservation.held.time` | Timer | domain, backend | Duration held |
| `reservation.expired` | Counter | domain, backend | Expirations before unlock |

### Example with Prometheus

```java
MeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

ReservationManager manager = ReservationManager.hazelcast(hz)
    .domain("orders")
    .meterRegistry(registry)
    .build();
```

## Exception Handling

| Exception | When Thrown | Handling |
|-----------|-------------|----------|
| `ReservationAcquisitionException` | Lock cannot be acquired (infrastructure issue) | Retry or fail operation |
| `ReservationExpiredException` | Lease expired before unlock | Log warning, handle inconsistency |
| `InvalidReservationKeyException` | Invalid identifier | Fix key validation |
| `IllegalStateException` | Building without required domain | Set domain on builder |
| `IllegalMonitorStateException` | Unlock without holding lock | Programming error |
| `UnsupportedOperationException` | `newCondition()` called | Not supported for distributed locks |

## Adding Another Backend

The public API is backend-agnostic. To add a new backend, implement `Reservation` and
`ReservationManager`, extend `AbstractReservationManagerBuilder` for its builder, and
run the shared contract tests (`AbstractReservationManagerTest`,
`AbstractStressIntegrationTest`) against the new implementation.

## Thread Safety

- `ReservationManager` implementations are thread-safe and immutable after construction
- `Reservation` instances are thread-safe but ownership is per-thread
- Reentrant locking is supported (same thread can lock multiple times)
- Each domain uses separate Hazelcast IMap for isolation

## Build & Test

```bash
# Compile
mvn clean compile

# Run unit tests
mvn clean test

# Run with integration tests (requires Docker for Testcontainers)
mvn clean verify -Pintegration-tests

# Package
mvn clean package -DskipTests
```

## Requirements

- Java 21+
- Maven 3.x
- Hazelcast 5.3.6

## License

Apache 2.0
