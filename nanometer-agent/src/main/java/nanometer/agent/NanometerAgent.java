package nanometer.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.lang.instrument.Instrumentation;

/**
 * Java Agent entrypoint supporting both -javaagent:nanometer.jar and dynamic attachment.
 */
@AICore(sensitivity = "High", note = "Java Agent entrypoint performing bytecode transformation")
@AIPublicAPI(reason = "Java Agent CLI and dynamic attach entrypoint")
public class NanometerAgent {

    /**
     * Set to {@code true} to print every failed transformation to {@code System.err}.
     * Transformation failures are otherwise silent, which once hid the agent instrumenting
     * nothing at all, so this is the supported way to diagnose an agent that records no spans.
     */
    private static final String DEBUG_PROPERTY = "nanometer.agent.debug";

    /**
     * Property naming packages to leave alone, comma separated, in addition to the built-in
     * exclusions. Only an include prefix was configurable before, so there was no way to instrument
     * a package while skipping a hot subpackage inside it.
     */
    private static final String EXCLUDE_PROPERTY = "nanometer.agent.exclude";

    public static void premain(@Nullable String agentArgs, Instrumentation inst) {
        installFromAgentArgs(agentArgs, inst);
    }

    public static void agentmain(@Nullable String agentArgs, Instrumentation inst) {
        installFromAgentArgs(agentArgs, inst);
    }

    private static void installFromAgentArgs(@Nullable String agentArgs, Instrumentation inst) {
        if (agentArgs == null || agentArgs.isBlank()) {
            // Instrumenting every loaded class records a span for every call in the process, its
            // dependencies included, which the ring buffer cannot absorb; the result is enormous
            // overhead and mostly-shed data. Refusing is more useful than doing that silently.
            System.err.println("[Nanometer] No package prefix given, so nothing will be instrumented. "
                    + "Pass one as an agent argument, for example "
                    + "-javaagent:nanometer.jar=com.example.order");
            return;
        }
        install(agentArgs, inst);
    }

    /**
     * @param packagePrefix package to instrument; must not be blank
     * @throws IllegalArgumentException if {@code packagePrefix} is blank
     */
    public static void install(String packagePrefix, Instrumentation inst) {
        if (packagePrefix == null || packagePrefix.isBlank()) {
            throw new IllegalArgumentException(
                    "packagePrefix must name the package to instrument; matching every type "
                            + "instruments the whole process including its dependencies");
        }

        AgentBuilder builder = new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(transformationListener())
                .ignore(ElementMatchers.nameStartsWith("net.bytebuddy.")
                        .or(ElementMatchers.nameStartsWith("nanometer.agent."))
                        .or(ElementMatchers.nameStartsWith("nanometer.buffer."))
                        .or(ElementMatchers.nameStartsWith("nanometer.model."))
                        .or(ElementMatchers.nameStartsWith("nanometer.storage."))
                        .or(ElementMatchers.nameStartsWith("nanometer.server."))
                        .or(ElementMatchers.nameStartsWith("nanometer.discovery."))
                        .or(ElementMatchers.nameStartsWith("nanometer.graph."))
                        .or(ElementMatchers.nameStartsWith("nanometer.sampling."))
                        .or(ElementMatchers.nameStartsWith("nanometer.profiling."))
                        .or(ElementMatchers.nameStartsWith("nanometer.trace."))
                        .or(ElementMatchers.nameStartsWith("java."))
                        .or(ElementMatchers.nameStartsWith("jdk."))
                        .or(ElementMatchers.nameStartsWith("sun.")));

        var matched = builder
                .ignore(excludedPackages())
                .type(ElementMatchers.nameStartsWith(packagePrefix));

        // Advice inlines the instrumentation into the target method. Unlike MethodDelegation with
        // @SuperCall it generates no auxiliary classes, so it needs no reflective class injection
        // into the target's class loader. That injection is unavailable on a modern JVM and used
        // to make every transformation fail silently.
        matched.transform((dynamicTypeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                dynamicTypeBuilder.visit(Advice.to(AutoMetricInterceptor.class)
                        .on(ElementMatchers.isMethod()
                                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                                .and(ElementMatchers.not(ElementMatchers.isNative()))
                                .and(ElementMatchers.not(ElementMatchers.isSynthetic()))
                                .and(ElementMatchers.not(ElementMatchers.isTypeInitializer()))
                                .and(ElementMatchers.not(ElementMatchers.isConstructor()))))
        ).installOn(inst);
    }

    /** User-supplied exclusions, layered on top of the built-in ignore list. */
    private static ElementMatcher.Junction<TypeDescription> excludedPackages() {
        ElementMatcher.Junction<TypeDescription> excluded = ElementMatchers.none();
        String configured = System.getProperty(EXCLUDE_PROPERTY, "");
        for (String prefix : configured.split(",")) {
            String trimmed = prefix.trim();
            if (!trimmed.isEmpty()) {
                excluded = excluded.or(ElementMatchers.nameStartsWith(trimmed));
            }
        }
        return excluded;
    }

    private static AgentBuilder.Listener transformationListener() {
        return Boolean.getBoolean(DEBUG_PROPERTY)
                ? AgentBuilder.Listener.StreamWriting.toSystemError()
                : AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly();
    }
}
