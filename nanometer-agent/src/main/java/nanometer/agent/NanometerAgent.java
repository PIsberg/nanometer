package nanometer.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.implementation.MethodDelegation;
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

    public static void premain(@Nullable String agentArgs, Instrumentation inst) {
        install(agentArgs != null && !agentArgs.isBlank() ? agentArgs : "", inst);
    }

    public static void agentmain(@Nullable String agentArgs, Instrumentation inst) {
        install(agentArgs != null && !agentArgs.isBlank() ? agentArgs : "", inst);
    }

    public static void install(String packagePrefix, Instrumentation inst) {
        AgentBuilder builder = new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .ignore(ElementMatchers.nameStartsWith("net.bytebuddy.")
                        .or(ElementMatchers.nameStartsWith("nanometer.agent."))
                        .or(ElementMatchers.nameStartsWith("nanometer.buffer."))
                        .or(ElementMatchers.nameStartsWith("nanometer.model."))
                        .or(ElementMatchers.nameStartsWith("nanometer.storage."))
                        .or(ElementMatchers.nameStartsWith("nanometer.server."))
                        .or(ElementMatchers.nameStartsWith("nanometer.discovery."))
                        .or(ElementMatchers.nameStartsWith("nanometer.graph."))
                        .or(ElementMatchers.nameStartsWith("java."))
                        .or(ElementMatchers.nameStartsWith("jdk."))
                        .or(ElementMatchers.nameStartsWith("sun.")));

        var matched = (packagePrefix != null && !packagePrefix.isBlank())
                ? builder.type(ElementMatchers.nameStartsWith(packagePrefix))
                : builder.type(ElementMatchers.any());

        matched.transform((dynamicTypeBuilder, typeDescription, classLoader, module, protectionDomain) ->
                dynamicTypeBuilder.method(ElementMatchers.isMethod()
                                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                                .and(ElementMatchers.not(ElementMatchers.isNative()))
                                .and(ElementMatchers.not(ElementMatchers.isSynthetic())))
                        .intercept(MethodDelegation.to(AutoMetricInterceptor.class))
        ).installOn(inst);
    }
}
