package nanometer.agent;

import nanometer.buffer.MetricRingBuffer;
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
}
