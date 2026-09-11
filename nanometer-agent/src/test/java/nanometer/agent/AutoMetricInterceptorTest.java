package nanometer.agent;

import nanometer.buffer.MetricRingBuffer;
import nanometer.sampling.AdaptiveSampler;
import nanometer.trace.W3CTraceContext;
import nanometer.model.RelationalMetricEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AutoMetricInterceptorTest {

    private MetricRingBuffer buffer;

    @BeforeEach
    public void setup() {
        buffer = new MetricRingBuffer(16);
        AutoMetricInterceptor.setBuffer(buffer);
    }

    public static class TestService {
        public String successfulCall() {
            return "SUCCESS";
        }

        public void failureCall() {
            throw new IllegalArgumentException("Test error");
        }
    }

    @Test
    public void testSuccessfulMethodInterception() throws Throwable {
        Method method = TestService.class.getMethod("successfulCall");
        TestService instance = new TestService();

        Object result = AutoMetricInterceptor.intercept(
                method,
                instance::successfulCall
        );

        assertEquals("SUCCESS", result);
        assertEquals(1, buffer.size());

        List<RelationalMetricEvent> events = buffer.drainAll();
        RelationalMetricEvent event = events.get(0);
        assertEquals(TestService.class.getName(), event.className());
        assertEquals("successfulCall", event.methodName());
        assertEquals("NONE", event.exceptionType());
        assertTrue(event.durationNs() >= 0);
        assertEquals(buffer, AutoMetricInterceptor.getBuffer());
    }

    @Test
    public void testExceptionMethodInterception() throws NoSuchMethodException {
        Method method = TestService.class.getMethod("failureCall");
        TestService instance = new TestService();

        assertThrows(IllegalArgumentException.class, () -> {
            AutoMetricInterceptor.intercept(
                    method,
                    () -> {
                        instance.failureCall();
                        return null;
                    }
            );
        });

        assertEquals(1, buffer.size());
        List<RelationalMetricEvent> events = buffer.drainAll();
        RelationalMetricEvent event = events.get(0);
        assertEquals("IllegalArgumentException", event.exceptionType());
    }

    @Test
    public void testNestedSpansCorrelation() throws Throwable {
        Method parentMethod = TestService.class.getMethod("successfulCall");
        Method childMethod = TestService.class.getMethod("successfulCall");
        TestService instance = new TestService();

        AutoMetricInterceptor.intercept(parentMethod, () -> {
            try {
                return AutoMetricInterceptor.intercept(childMethod, instance::successfulCall);
            } catch (Throwable t) {
                if (t instanceof Exception e) throw e;
                throw new RuntimeException(t);
            }
        });

        assertEquals(2, buffer.size());
        List<RelationalMetricEvent> events = buffer.drainAll();
        // Child event finishes first
        RelationalMetricEvent child = events.get(0);
        RelationalMetricEvent parent = events.get(1);

        assertEquals(parent.traceIdHex(), child.traceIdHex(),
                "a nested call shares the caller's 128-bit trace id");
        assertEquals(parent.currentSpanId(), child.parentSpanId());

        assertTrue(child.hasParent(), "the nested span carries its caller's identity");
        assertEquals(parent.className(), child.parentClassName());
        assertEquals(parent.methodName(), child.parentMethodName());

        assertFalse(parent.hasParent(), "the outermost span has no caller to name");
        assertEquals(0L, parent.parentSpanId());
    }

    @Test
    public void spanStartTimestampPrecedesTheRecordedEnd() throws Throwable {
        long before = System.currentTimeMillis();
        Method method = TestService.class.getMethod("successfulCall");
        TestService instance = new TestService();

        AutoMetricInterceptor.intercept(method, instance::successfulCall);
        long after = System.currentTimeMillis();

        RelationalMetricEvent event = buffer.drainAll().get(0);

        // The timestamp used to be taken at exit and read back as the start, which shifted every
        // exported span later by its own duration.
        assertTrue(event.startTimestamp() >= before,
                "start timestamp " + event.startTimestamp() + " predates the call");
        assertTrue(event.startTimestamp() <= after,
                "start timestamp " + event.startTimestamp() + " is after the call returned");
    }

    @Test
    public void testAgentInstallationAndMainEntrypoints() {
        Instrumentation mockInst = (Instrumentation) Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("isRedefineClassesSupported")) return true;
                    if (method.getName().equals("isRetransformClassesSupported")) return true;
                    if (method.getName().equals("getAllLoadedClasses")) return new Class<?>[0];
                    if (method.getName().equals("getInitiatedClasses")) return new Class<?>[0];
                    return null;
                }
        );

        NanometerAgent.install("nanometer.agent", mockInst);
        NanometerAgent.premain("nanometer.agent", mockInst);
        NanometerAgent.premain("", mockInst);
        NanometerAgent.agentmain("nanometer.agent", mockInst);
        NanometerAgent.agentmain(null, mockInst);
        NanometerAgent agent = new NanometerAgent();
        assertNotNull(agent);
    }

    @Test
    public void aDroppedTraceDropsEveryOneOfItsSpansNotARandomSubset() throws Throwable {
        // Rate 0 with tail sampling off means nothing is interesting, so the whole trace goes.
        AdaptiveSampler sampler = new AdaptiveSampler();
        sampler.setTailSamplingEnabled(false);
        sampler.setSampleRate(0.0);
        AutoMetricInterceptor.setSampler(sampler);
        try {
            Method parentMethod = TestService.class.getMethod("successfulCall");
            Method childMethod = TestService.class.getMethod("successfulCall");
            TestService instance = new TestService();

            AutoMetricInterceptor.intercept(parentMethod, () ->
                    nested(childMethod, instance::successfulCall));

            assertEquals(0, buffer.size(), "a dropped trace must leave no orphaned children behind");
        } finally {
            AutoMetricInterceptor.setSampler(new AdaptiveSampler());
        }
    }

    @Test
    public void aKeptTraceKeepsEveryOneOfItsSpans() throws Throwable {
        AdaptiveSampler sampler = new AdaptiveSampler();
        sampler.setTailSamplingEnabled(false);
        sampler.setSampleRate(1.0);
        AutoMetricInterceptor.setSampler(sampler);
        try {
            Method method = TestService.class.getMethod("successfulCall");
            TestService instance = new TestService();

            AutoMetricInterceptor.intercept(method, () ->
                    nested(method, instance::successfulCall));

            assertEquals(2, buffer.size(), "both spans of the trace are kept together");
        } finally {
            AutoMetricInterceptor.setSampler(new AdaptiveSampler());
        }
    }

    @Test
    public void aTraceContainingAnErrorIsKeptEvenAtZeroRate() throws Throwable {
        AdaptiveSampler sampler = new AdaptiveSampler();
        sampler.setSampleRate(0.0);
        sampler.setTailSamplingEnabled(true);
        AutoMetricInterceptor.setSampler(sampler);
        try {
            Method outer = TestService.class.getMethod("successfulCall");
            Method failing = TestService.class.getMethod("failureCall");
            TestService instance = new TestService();

            assertThrows(IllegalArgumentException.class, () ->
                    AutoMetricInterceptor.intercept(outer, () -> {
                        nested(failing, () -> {
                            instance.failureCall();
                            return null;
                        });
                        return null;
                    }));

            // Retaining a failing trace whatever the rate says is the point of tail sampling, and
            // it must retain the caller's span too or the failure has no context.
            assertEquals(2, buffer.size(), "an error trace is kept whole, at any sample rate");
        } finally {
            AutoMetricInterceptor.setSampler(new AdaptiveSampler());
        }
    }

    @Test
    public void aFailingChildKeepsItsCallerEvenWhenTheCallerSwallowsTheError() throws Throwable {
        // The decisive case for per-trace versus per-span sampling. At rate 0 with tail sampling on,
        // a per-span decision keeps the throwing child (it looks interesting) and drops the caller
        // that caught the error (fast, no exception), leaving an orphan whose parent span is gone
        // and an edge the topology graph can never draw.
        AdaptiveSampler sampler = new AdaptiveSampler();
        sampler.setSampleRate(0.0);
        sampler.setTailSamplingEnabled(true);
        AutoMetricInterceptor.setSampler(sampler);
        try {
            Method outer = TestService.class.getMethod("successfulCall");
            Method failing = TestService.class.getMethod("failureCall");
            TestService instance = new TestService();

            AutoMetricInterceptor.intercept(outer, () -> {
                try {
                    nested(failing, () -> {
                        instance.failureCall();
                        return null;
                    });
                } catch (IllegalArgumentException handled) {
                    // The caller handles it, so its own span exits cleanly.
                }
                return "SUCCESS";
            });

            List<RelationalMetricEvent> events = buffer.drainAll();
            assertEquals(2, events.size(),
                    "the trace contains an error so all of it is kept, caller included; a per-span "
                            + "decision keeps only the throwing child. Got: " + events);
            assertTrue(events.stream().anyMatch(e -> !e.hasException()),
                    "the clean caller span must survive alongside the failing one");
        } finally {
            AutoMetricInterceptor.setSampler(new AdaptiveSampler());
        }
    }

    @Test
    public void spansJoinAnAdoptedInboundTraceInsteadOfStartingAFreshOne() throws Throwable {
        // The whole point of W3C trace context: a trace crosses the process boundary. Nothing
        // consulted W3CTraceContext before, so every service restarted the trace at its edge.
        String inbound = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        W3CTraceContext.TraceSpan adopted = W3CTraceContext.adoptIncoming(inbound);
        assertNotNull(adopted);
        try {
            Method method = TestService.class.getMethod("successfulCall");
            TestService instance = new TestService();

            AutoMetricInterceptor.intercept(method, instance::successfulCall);

            RelationalMetricEvent event = buffer.drainAll().get(0);
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", event.traceIdHex(),
                    "the span must carry the caller's trace id, not a locally invented one");
            assertEquals(adopted.parentSpanId(), event.parentSpanId(),
                    "the local root descends from the remote caller's span");
            assertNotEquals(0L, event.parentSpanId(),
                    "a continued trace's root is not parentless");
        } finally {
            W3CTraceContext.clear();
        }
    }

    @Test
    public void spansStartAFreshTraceWhenThereIsNoInboundContext() throws Throwable {
        W3CTraceContext.clear();
        Method method = TestService.class.getMethod("successfulCall");
        TestService instance = new TestService();

        AutoMetricInterceptor.intercept(method, instance::successfulCall);

        RelationalMetricEvent event = buffer.drainAll().get(0);
        assertEquals(0L, event.parentSpanId(), "a root with no inbound context has no parent");
        assertNotEquals("00000000000000000000000000000000", event.traceIdHex());
    }

    @Test
    public void aLongRunningRootStillPublishesItsSpans() throws Throwable {
        // Found by running a service under the agent rather than by the suite: tail sampling waits
        // for the root to close, and a service whose main or accept loop is instrumented has a root
        // that never closes, so nothing reached the dashboard at all. Past the bound a trace stops
        // being treated as a request and its spans flow.
        long original = AutoMetricInterceptor.getMaxTraceBufferNanos();
        AutoMetricInterceptor.setMaxTraceBufferNanos(1L);
        try {
            Method method = TestService.class.getMethod("successfulCall");
            TestService instance = new TestService();

            // The root stays open while inner calls complete underneath it.
            AutoMetricInterceptor.intercept(method, () -> {
                for (int i = 0; i < 5; i++) {
                    nested(method, instance::successfulCall);
                }
                assertTrue(buffer.size() > 0,
                        "spans must become visible while the root is still open; buffering them "
                                + "until it closes hides everything for a service that never returns");
                return "SUCCESS";
            });

            assertTrue(AutoMetricInterceptor.getDegradedTraceCount() > 0,
                    "the fallback should be counted, not silent");
        } finally {
            AutoMetricInterceptor.setMaxTraceBufferNanos(original);
        }
    }

    /** Callable.call declares Exception, but intercept declares Throwable; bridge the two. */
    private static Object nested(Method method, java.util.concurrent.Callable<?> inner) throws Exception {
        try {
            return AutoMetricInterceptor.intercept(method, inner);
        } catch (Throwable t) {
            if (t instanceof Exception e) {
                throw e;
            }
            throw new RuntimeException(t);
        }
    }
}
