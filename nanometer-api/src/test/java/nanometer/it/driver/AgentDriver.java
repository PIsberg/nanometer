package nanometer.it.driver;

import nanometer.it.subject.AgentTarget;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Drives {@link AgentTarget} inside a JVM forked by {@code ShadedAgentIT} and prints what the
 * agent recorded, so the parent process can assert on it.
 *
 * <p>This class is intentionally outside {@code nanometer.it.subject}, the package the agent is
 * told to instrument. Keeping the driver uninstrumented is what makes
 * {@link AgentTarget#parent()} the outermost span.
 *
 * <p>The ring buffer is read reflectively because the agent classes arrive from the shaded
 * uber-JAR that {@code -javaagent} appends to the system class path, not from this module.
 */
public final class AgentDriver {

    private AgentDriver() {
    }

    public static void main(String[] args) throws Exception {
        AgentTarget target = new AgentTarget();
        target.parent();
        try {
            target.boom();
        } catch (IllegalStateException expected) {
            // recorded by the agent as an exceptional exit
        }

        Class<?> interceptor = Class.forName("nanometer.agent.AutoMetricInterceptor");
        Object buffer = interceptor.getMethod("getBuffer").invoke(null);
        List<?> events = (List<?>) buffer.getClass().getMethod("drainAll").invoke(buffer);

        for (Object event : events) {
            Class<?> type = event.getClass();
            System.out.println("EVENT"
                    + " class=" + value(type, event, "className")
                    + " method=" + value(type, event, "methodName")
                    + " traceId=" + value(type, event, "traceIdHex")
                    + " spanId=" + value(type, event, "currentSpanId")
                    + " parentSpanId=" + value(type, event, "parentSpanId")
                    + " exception=" + value(type, event, "exceptionType")
                    + " durationNs=" + value(type, event, "durationNs"));
        }
        System.out.println("EVENT_COUNT=" + events.size());
    }

    private static Object value(Class<?> type, Object event, String accessor) throws Exception {
        Method method = type.getMethod(accessor);
        return method.invoke(event);
    }
}
