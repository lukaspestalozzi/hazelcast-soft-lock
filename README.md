# Reservation Lock Library

A distributed soft-lock library for Java implementing `java.util.concurrent.locks.Lock` with automatic lease expiration. Prevents deadlocks from crashed processes or forgotten unlocks.

## Features

- **Implements `java.util.concurrent.locks.Lock`** - Familiar API for Java developers
- **Automatic expiration** - Locks auto-release after configurable lease time (default: 1 minute)
- **Two backends** - Hazelcast (IMap.lock) and Oracle DB (table-based with polling)
- **Reentrant locking** - Same thread can acquire the same lock multiple times
- **Micrometer metrics** - Built-in observability support
- **Domain + Identifier keys** - Logical grouping (e.g., `orders::12345`)

## Installation

```xml
<dependency>
    <groupId>com.github.reservation</groupId>
    <artifactId>reservation-lock</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Quick Start

### Hazelcast Backend

```java
HazelcastInstance hz = HazelcastClient.newHazelcastClient();
ReservationManager manager = ReservationManager.hazelcast(hz)
    .leaseTime(Duration.ofMinutes(2))
    .mapName("my-reservations")
    .build();

Reservation reservation = manager.getReservation("orders", "order-12345");
reservation.lock();
try {
    // Critical section - only one process can execute this
    processOrder("order-12345");
} finally {
    reservation.unlock();
}
```

### Oracle Backend

```java
DataSource dataSource = getDataSource(); // Your connection pool
ReservationManager manager = ReservationManager.oracle(dataSource)
    .leaseTime(Duration.ofMinutes(2))
    .tableName("RESERVATION_LOCKS")
    .build();

Reservation reservation = manager.getReservation("orders", "order-12345");
reservation.lock();
try {
    processOrder("order-12345");
} finally {
    reservation.unlock();
}
```

### Try-Lock Pattern

```java
Reservation reservation = manager.getReservation("inventory", "sku-ABC123");
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
| `leaseTime` | `Duration` | 1 minute | Auto-release time |
| `delimiter` | `String` | `::` | Key separator |
| `meterRegistry` | `MeterRegistry` | null | Micrometer metrics |

### Hazelcast-Specific

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `mapName` | `String` | `reservations` | IMap name |

### Oracle-Specific

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `tableName` | `String` | `RESERVATION_LOCKS` | Table name |
| `lockingStrategy` | `LockingStrategy` | `TableBasedLockingStrategy` | Pluggable strategy |

## Oracle Database Setup

Create the required table before using the Oracle backend:

```sql
CREATE TABLE RESERVATION_LOCKS (
    reservation_key  VARCHAR2(512)  NOT NULL,
    holder           VARCHAR2(256)  NOT NULL,
    acquired_at      TIMESTAMP      NOT NULL,
    expires_at       TIMESTAMP      NOT NULL,
    CONSTRAINT pk_reservation_locks PRIMARY KEY (reservation_key)
);

-- Index for efficient cleanup of expired locks
CREATE INDEX idx_reservation_locks_expires ON RESERVATION_LOCKS (expires_at);

-- Optional: Grant permissions
GRANT SELECT, INSERT, UPDATE, DELETE ON RESERVATION_LOCKS TO app_user;
```

### For H2 (Testing)

```sql
CREATE TABLE RESERVATION_LOCKS (
    reservation_key  VARCHAR(512)  NOT NULL,
    holder           VARCHAR(256)  NOT NULL,
    acquired_at      TIMESTAMP     NOT NULL,
    expires_at       TIMESTAMP     NOT NULL,
    PRIMARY KEY (reservation_key)
);
```

### Scheduled Cleanup (Optional)

```sql
BEGIN
    DBMS_SCHEDULER.CREATE_JOB(
        job_name        => 'CLEANUP_EXPIRED_RESERVATIONS',
        job_type        => 'PLSQL_BLOCK',
        job_action      => 'DELETE FROM RESERVATION_LOCKS WHERE expires_at < SYSTIMESTAMP;',
        repeat_interval => 'FREQ=HOURLY',
        enabled         => TRUE
    );
END;
/
```

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
    .meterRegistry(registry)
    .build();
```

## Exception Handling

| Exception | When Thrown | Handling |
|-----------|-------------|----------|
| `ReservationAcquisitionException` | Lock cannot be acquired (infrastructure issue) | Retry or fail operation |
| `ReservationExpiredException` | Lease expired before unlock | Log warning, handle inconsistency |
| `InvalidReservationKeyException` | Invalid domain/identifier | Fix key validation |
| `IllegalMonitorStateException` | Unlock without holding lock | Programming error |
| `UnsupportedOperationException` | `newCondition()` called | Not supported for distributed locks |

## Custom Locking Strategy (Oracle)

Implement `LockingStrategy` for custom Oracle locking mechanisms:

```java
public class MyCustomStrategy implements LockingStrategy {
    @Override
    public boolean tryAcquire(String key, String holder, Duration leaseTime) {
        // Custom implementation
    }

    @Override
    public boolean release(String key, String holder) {
        // Custom implementation
    }

    // ... other methods
}

ReservationManager manager = ReservationManager.oracle(dataSource)
    .lockingStrategy(new MyCustomStrategy())
    .build();
```

## Choosing Between Backends

| Use Case | Recommended |
|----------|-------------|
| Already using Hazelcast | Hazelcast |
| Low latency required (< 1ms) | Hazelcast |
| Only Oracle infrastructure available | Oracle |
| Need queryable audit trail | Oracle |
| Strong ACID requirements | Oracle |

## Thread Safety

- `ReservationManager` implementations are thread-safe and immutable after construction
- `Reservation` instances are thread-safe but ownership is per-thread
- Reentrant locking is supported (same thread can lock multiple times)

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
- Hazelcast 5.3.6 (for Hazelcast backend)
- Oracle Database or H2 (for JDBC backend)

## License

Apache 2.0
