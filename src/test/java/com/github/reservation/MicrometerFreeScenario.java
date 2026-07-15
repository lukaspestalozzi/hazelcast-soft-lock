package com.github.reservation;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

import java.time.Duration;
import java.util.UUID;

/**
 * A complete library usage scenario that must run on a classpath WITHOUT Micrometer.
 *
 * <p>Loaded and executed reflectively by {@code MicrometerOptionalityTest} inside a
 * classloader that refuses to load {@code io.micrometer.*}. This class must therefore
 * never reference Micrometer types (and never call {@code meterRegistry(...)}).</p>
 */
public final class MicrometerFreeScenario {

    private MicrometerFreeScenario() {
    }

    public static void run() throws Exception {
        Config config = new Config();
        config.setClusterName("micrometer-free-" + UUID.randomUUID());
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);

        HazelcastInstance hazelcast = Hazelcast.newHazelcastInstance(config);
        try {
            ReservationManager manager = ReservationManager.hazelcast(hazelcast)
                .domain("plain")
                .leaseTime(Duration.ofSeconds(5))
                .build();

            Reservation reservation = manager.getReservation("no-micrometer");
            reservation.lock();
            if (!reservation.isHeldByCurrentThread()) {
                throw new IllegalStateException("expected to hold the reservation");
            }
            reservation.unlock();

            if (!reservation.tryLock()) {
                throw new IllegalStateException("tryLock should succeed on a free reservation");
            }
            reservation.unlock();

            manager.close();
        } finally {
            hazelcast.shutdown();
        }
    }
}
