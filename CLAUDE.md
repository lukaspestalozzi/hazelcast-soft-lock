# CLAUDE.md - Reservation Lock Library

## Project Overview

Distributed soft-lock library implementing `java.util.concurrent.locks.Lock` with automatic lease expiration. Supports two backends: **Hazelcast** (using IMap.lock) and **Oracle/JDBC** (using table-based locking with polling).

## Tech Stack

- Java 21
- Maven 3.x
- Hazelcast 5.3.6 (optional dependency)
- H2 for tests, Oracle for production JDBC backend
- JUnit 5, AssertJ, Testcontainers

## Project Structure

```
src/main/java/com/github/reservation/
├── Reservation.java              # Main Lock interface
├── ReservationManager.java       # Factory with builder pattern
├── hazelcast/                    # Hazelcast backend
│   └── HazelcastReservation.java
└── oracle/                       # JDBC/Oracle backend
    ├── LockingStrategy.java      # Pluggable locking strategy interface
    ├── TableBasedLockingStrategy.java
    └── OracleReservation.java
```

For detailed architecture, see `docs/DESIGN.md`.

## Build & Test Commands

**IMPORTANT: Always run tests locally before pushing:**

```bash
# Run unit tests (required before every push)
mvn clean test

# Run with integration tests
mvn clean verify -Pintegration-tests

# Compile only
mvn clean compile

# Package JAR
mvn clean package -DskipTests
```

## Proxy Workaround (Authenticated Proxy Environments)

Java's `HttpURLConnection` does not send proxy authentication for HTTPS CONNECT requests. This causes Maven to fail with "Temporary failure in name resolution" errors in environments with authenticated proxies.

### Solution: Local Auth Proxy

The `scripts/auth-proxy.py` script creates a local proxy that:
1. Reads credentials from `HTTP_PROXY`/`HTTPS_PROXY` environment variables
2. Listens on `127.0.0.1:3128`
3. Injects `Proxy-Authorization` header into CONNECT requests
4. Forwards traffic to the upstream authenticated proxy

### Setup Steps

1. **Start the auth proxy:**
   ```bash
   python3 scripts/auth-proxy.py &
   ```

2. **Configure Maven** (create/update `~/.m2/settings.xml`):
   ```xml
   <settings>
     <proxies>
       <proxy>
         <id>local-auth-proxy</id>
         <active>true</active>
         <protocol>http</protocol>
         <host>127.0.0.1</host>
         <port>3128</port>
       </proxy>
       <proxy>
         <id>local-auth-proxy-https</id>
         <active>true</active>
         <protocol>https</protocol>
         <host>127.0.0.1</host>
         <port>3128</port>
       </proxy>
     </proxies>
   </settings>
   ```

3. **Run Maven normally:**
   ```bash
   mvn clean test
   ```

### Convenience Script

Use `scripts/run-maven-with-proxy.sh` to automatically start the proxy and run Maven:
```bash
./scripts/run-maven-with-proxy.sh clean test
```

## Code Conventions

- Exceptions: Use checked exceptions (`ReservationException` hierarchy)
- Key format: `{domain}::{identifier}` (delimiter configurable)
- Thread safety: Implementations must be thread-safe; use ThreadLocal for ownership tracking
- Reentrancy: Both backends support reentrant locking

## Testing Notes

- `AbstractReservationManagerTest` contains 24+ shared tests for both backends
- Hazelcast tests use embedded instance (no external dependencies)
- JDBC tests use H2 in-memory database
- Integration tests (with Testcontainers) require `-Pintegration-tests` profile

## CI/CD

GitHub Actions workflow in `.github/workflows/ci.yml`:
- Builds with Java 21
- Runs unit tests on every push
- Runs integration tests after unit tests pass
- Packages JAR artifact
