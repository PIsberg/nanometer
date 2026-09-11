package nanometer.it;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the embedded install path with the shaded uber-JAR on the classpath and asserts telemetry
 * reaches SQLite and can be read back.
 *
 * <p>Nothing covered this. Both of the defects it now pins were invisible to a green build because
 * the unit suite uses the unshaded module classpath:
 * <ul>
 *   <li>shade rewrote the relocated class names but not {@code META-INF/services/java.sql.Driver},
 *       so the entry still named {@code org.sqlite.JDBC} and DriverManager reported
 *       "No suitable driver found for jdbc:sqlite:" from a JAR that contained the driver;</li>
 *   <li>relocating sqlite-jdbc, a JNI library, left its native binary exporting
 *       {@code Java_org_sqlite_core_NativeDB_*}, so the first query died with
 *       {@code NoClassDefFoundError: org/sqlite/core/NativeDB}.</li>
 * </ul>
 */
@DisplayName("Shaded JAR stores and reads telemetry")
class ShadedStorageIT {

    private static Path shadedJar;
    private static Path itClasses;

    @BeforeAll
    static void locateArtifacts() {
        shadedJar = Path.of(required("nanometer.shaded.jar"));
        itClasses = Path.of(required("nanometer.it.classes"));
        assertTrue(Files.isRegularFile(shadedJar), "shaded JAR not found: " + shadedJar);
    }

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is not set; run under maven-failsafe-plugin");
        }
        return value;
    }

    @Test
    @DisplayName("writes spans to SQLite and reads them back through the read-only connection")
    void storesAndReadsTelemetry() throws Exception {
        Path workingDir = Files.createTempDirectory("nanometer-storage-it");

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(
                java,
                "-XX:+EnableDynamicAgentLoading",
                "-Djdk.attach.allowAttachSelf=true",
                "-cp", shadedJar.toAbsolutePath() + java_separator() + itClasses.toAbsolutePath(),
                "nanometer.it.driver.StorageDriver");
        builder.directory(workingDir.toFile());
        builder.redirectErrorStream(true);

        Process process = builder.start();
        List<String> output = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
        }
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            fail("forked JVM did not exit. Output:\n" + String.join("\n", output));
        }

        assertEquals(0, process.exitValue(),
                "the embedded install path failed from the shaded JAR. Output:\n"
                        + String.join("\n", output));

        String rows = output.stream()
                .filter(l -> l.startsWith("ROWS="))
                .map(l -> l.substring("ROWS=".length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "driver produced no row count. Output:\n" + String.join("\n", output)));

        assertTrue(Integer.parseInt(rows) > 0,
                "no telemetry reached SQLite from the shaded JAR. Output:\n"
                        + String.join("\n", output));
        assertTrue(Files.exists(workingDir.resolve(".nanometer").resolve("metrics.db")),
                "the embedded database was never created");
    }

    private static String java_separator() {
        return System.getProperty("path.separator");
    }
}
