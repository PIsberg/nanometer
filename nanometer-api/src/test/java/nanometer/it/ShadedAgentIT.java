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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the shaded uber-JAR as a real {@code -javaagent} in a forked JVM and asserts that it
 * records spans.
 *
 * <p>This exists because the unit suite calls {@code AutoMetricInterceptor.intercept} directly and
 * therefore passes whether or not the agent wires anything up. The agent previously failed every
 * transformation with "Reflection-based injection is not available on the current VM" and recorded
 * nothing, and no test noticed.
 */
@DisplayName("Shaded JAR works as a Java agent")
class ShadedAgentIT {

    /**
     * The package the agent is told to instrument. The driver that calls into it sits outside this
     * package on purpose, so the target's entry method is the outermost instrumented frame.
     */
    private static final String INSTRUMENTED_PACKAGE = "nanometer.it.subject";

    private static Path shadedJar;
    private static Path itClasses;

    @BeforeAll
    static void locateArtifacts() {
        shadedJar = Path.of(requiredProperty("nanometer.shaded.jar"));
        itClasses = Path.of(requiredProperty("nanometer.it.classes"));
        assertTrue(Files.isRegularFile(shadedJar), "shaded JAR not found: " + shadedJar);
        assertTrue(Files.isDirectory(itClasses), "IT classes not found: " + itClasses);
    }

    private static String requiredProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("system property " + key + " is not set; "
                    + "this test must run under maven-failsafe-plugin");
        }
        return value;
    }

    @Test
    @DisplayName("records a span for every instrumented method call")
    void recordsSpans() throws Exception {
        List<String> output = runTargetUnderAgent();
        List<Map<String, String>> events = parseEvents(output);

        assertNotEquals(0, events.size(),
                "agent recorded no spans; transformation silently failed. Output:\n"
                        + String.join("\n", output));

        assertTrue(events.stream().anyMatch(e -> "parent".equals(e.get("method"))),
                "no span recorded for parent(). Events: " + events);
        assertTrue(events.stream().anyMatch(e -> "child".equals(e.get("method"))),
                "no span recorded for child(). Events: " + events);
    }

    @Test
    @DisplayName("correlates a nested call to its caller's trace and span")
    void correlatesNestedSpans() throws Exception {
        List<Map<String, String>> events = parseEvents(runTargetUnderAgent());

        Map<String, String> parent = single(events, "parent");
        Map<String, String> child = single(events, "child");

        assertEquals(parent.get("traceId"), child.get("traceId"),
                "nested call must share the caller's trace id");
        assertEquals(parent.get("spanId"), child.get("parentSpanId"),
                "nested call must point at the caller's span as its parent");
        assertEquals("0", parent.get("parentSpanId"),
                "the outermost call must have no parent span");
    }

    @Test
    @DisplayName("records the exception type when an instrumented method throws")
    void recordsExceptionType() throws Exception {
        List<Map<String, String>> events = parseEvents(runTargetUnderAgent());

        Map<String, String> boom = single(events, "boom");
        assertEquals("IllegalStateException", boom.get("exception"),
                "exceptional exit must be recorded with its exception type");
    }

    @Test
    @DisplayName("instruments a class defined by a child class loader")
    void instrumentsChildLoadedClasses() throws Exception {
        List<String> output = runUnderAgent("nanometer.it.driver.ChildLoaderDriver",
                itClasses.toAbsolutePath().toString());
        List<Map<String, String>> events = parseEvents(output);

        assertNotEquals(0, events.size(),
                "agent recorded nothing for a child-loaded class. Advice inlines a direct "
                        + "reference to AutoMetricInterceptor into the instrumented class, so this "
                        + "fails if the interceptor is not visible from that class's loader. "
                        + "Output:" + System.lineSeparator() + String.join(System.lineSeparator(), output));

        assertTrue(events.stream().anyMatch(e -> "parent".equals(e.get("method"))),
                "no span recorded for the child-loaded parent(). Events: " + events);
        assertTrue(events.stream().anyMatch(e -> "child".equals(e.get("method"))),
                "no span recorded for the child-loaded child(). Events: " + events);
    }

    private static Map<String, String> single(List<Map<String, String>> events, String method) {
        List<Map<String, String>> matches = events.stream()
                .filter(e -> method.equals(e.get("method")))
                .collect(Collectors.toList());
        if (matches.size() != 1) {
            fail("expected exactly one span for " + method + " but found " + matches.size()
                    + ". Events: " + events);
        }
        return matches.get(0);
    }

    /**
     * Forks a JVM with the shaded JAR attached as an agent. The agent JAR is deliberately not on
     * {@code -cp}: {@code -javaagent} appends it to the system class path itself, which is exactly
     * how a user would run it.
     */
    private static List<String> runTargetUnderAgent() throws Exception {
        return runUnderAgent("nanometer.it.driver.AgentDriver");
    }

    private static List<String> runUnderAgent(String mainClass, String... args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(
                java,
                "-javaagent:" + shadedJar.toAbsolutePath() + "=" + INSTRUMENTED_PACKAGE,
                "-cp", itClasses.toAbsolutePath().toString(),
                mainClass));
        command.addAll(List.of(args));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            fail("forked JVM did not exit. Output:\n" + String.join("\n", lines));
        }
        assertEquals(0, process.exitValue(),
                "forked JVM failed. Output:\n" + String.join("\n", lines));
        return lines;
    }

    private static List<Map<String, String>> parseEvents(List<String> output) {
        List<Map<String, String>> events = new ArrayList<>();
        for (String line : output) {
            if (!line.startsWith("EVENT ")) {
                continue;
            }
            Map<String, String> event = new java.util.LinkedHashMap<>();
            for (String token : line.substring("EVENT ".length()).split(" ")) {
                int eq = token.indexOf('=');
                if (eq > 0) {
                    event.put(token.substring(0, eq), token.substring(eq + 1));
                }
            }
            events.add(event);
        }
        return events;
    }
}
