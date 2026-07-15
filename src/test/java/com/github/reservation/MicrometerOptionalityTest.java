package com.github.reservation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the claim that Micrometer is a genuinely optional dependency: the whole
 * lock/unlock lifecycle must work when {@code io.micrometer.*} cannot be loaded.
 *
 * <p>The library relies on lazy linkage (Micrometer types only appear in method
 * signatures and untaken branches unless a registry is configured). That property is
 * fragile — a stray field initializer or eager reference would break it silently —
 * so this test pins it: {@link MicrometerFreeScenario} is re-loaded inside a
 * classloader that throws {@link ClassNotFoundException} for every Micrometer class
 * and then executed end to end.</p>
 */
class MicrometerOptionalityTest {

    @Test
    @Timeout(120)
    void libraryMustWorkWithoutMicrometerOnTheClasspath() throws Exception {
        URL[] classpath = codeSourcesOf(
            Reservation.class,                  // target/classes (library under test)
            MicrometerFreeScenario.class,       // target/test-classes (the scenario)
            com.hazelcast.core.Hazelcast.class, // hazelcast jar
            org.slf4j.Logger.class,             // slf4j-api jar
            ch.qos.logback.classic.Logger.class,// logback-classic jar
            ch.qos.logback.core.Context.class   // logback-core jar
        );

        try (URLClassLoader micrometerFreeLoader =
                 new URLClassLoader(classpath, ClassLoader.getPlatformClassLoader()) {
                     @Override
                     protected Class<?> loadClass(String name, boolean resolve)
                             throws ClassNotFoundException {
                         if (name.startsWith("io.micrometer.")) {
                             throw new ClassNotFoundException(
                                 "Micrometer is excluded from this classpath: " + name);
                         }
                         return super.loadClass(name, resolve);
                     }
                 }) {

            // Sanity check: Micrometer really is unloadable in this world
            assertThatThrownBy(() ->
                Class.forName("io.micrometer.core.instrument.MeterRegistry",
                    false, micrometerFreeLoader))
                .isInstanceOf(ClassNotFoundException.class);

            Class<?> scenario = Class.forName(
                MicrometerFreeScenario.class.getName(), true, micrometerFreeLoader);
            assertThat(scenario.getClassLoader())
                .as("scenario must run inside the filtered classloader")
                .isSameAs(micrometerFreeLoader);

            Thread current = Thread.currentThread();
            ClassLoader previousContextLoader = current.getContextClassLoader();
            current.setContextClassLoader(micrometerFreeLoader);
            try {
                // Throws (e.g. NoClassDefFoundError for io.micrometer.*) if the
                // library links against Micrometer on the registry-less path
                scenario.getMethod("run").invoke(null);
            } finally {
                current.setContextClassLoader(previousContextLoader);
            }
        }
    }

    private static URL[] codeSourcesOf(Class<?>... classes) {
        return Arrays.stream(classes)
            .map(c -> c.getProtectionDomain().getCodeSource().getLocation())
            .distinct()
            .toArray(URL[]::new);
    }
}
