package nanometer.it.driver;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Drives an instrumented class that is defined by a <em>child</em> class loader rather than by the
 * system class path, and prints what the agent recorded.
 *
 * <p>Advice inlines its body into the instrumented method, so the instrumented class ends up
 * holding a direct reference to {@code nanometer.agent.AutoMetricInterceptor}. That only resolves
 * if the interceptor is visible from the instrumented class's loader. Applications Nanometer is
 * meant to sit inside, Spring Boot fat JARs and servlet containers among them, load application
 * classes in a child loader, so this is the deployment shape that matters and it is not covered by
 * driving classes off the system class path.
 */
public final class ChildLoaderDriver {

    private static final String TARGET = "nanometer.it.subject.AgentTarget";

    private ChildLoaderDriver() {
    }

    /**
     * Loads {@link #TARGET} itself instead of delegating, so the class really is defined by this
     * loader. Everything else, including the agent classes, still comes from the parent.
     */
    private static final class ChildFirstLoader extends ClassLoader {

        private final Path classesDir;

        ChildFirstLoader(Path classesDir, ClassLoader parent) {
            super(parent);
            this.classesDir = classesDir;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!TARGET.equals(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        byte[] bytes = Files.readAllBytes(
                                classesDir.resolve(name.replace('.', '/') + ".class"));
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (Exception e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path classesDir = Path.of(args[0]);

        ChildFirstLoader loader = new ChildFirstLoader(classesDir, ChildLoaderDriver.class.getClassLoader());
        Class<?> target = loader.loadClass(TARGET);

        if (target.getClassLoader() != loader) {
            throw new IllegalStateException("target was not defined by the child loader but by "
                    + target.getClassLoader() + "; the test would prove nothing");
        }

        Object instance = target.getDeclaredConstructor().newInstance();
        target.getMethod("parent").invoke(instance);

        Class<?> interceptor = Class.forName("nanometer.agent.AutoMetricInterceptor");
        Object buffer = interceptor.getMethod("getBuffer").invoke(null);
        List<?> events = (List<?>) buffer.getClass().getMethod("drainAll").invoke(buffer);

        for (Object event : events) {
            Class<?> type = event.getClass();
            System.out.println("EVENT"
                    + " class=" + value(type, event, "className")
                    + " method=" + value(type, event, "methodName")
                    + " traceId=" + value(type, event, "traceId")
                    + " spanId=" + value(type, event, "currentSpanId")
                    + " parentSpanId=" + value(type, event, "parentSpanId")
                    + " exception=" + value(type, event, "exceptionType"));
        }
        System.out.println("EVENT_COUNT=" + events.size());
    }

    private static Object value(Class<?> type, Object event, String accessor) throws Exception {
        Method method = type.getMethod(accessor);
        return method.invoke(event);
    }
}
