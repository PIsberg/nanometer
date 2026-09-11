package nanometer.sampling;

import nanometer.model.RelationalMetricEvent;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dynamic runtime adaptive sampler controlling tail-sampling, sampling rates, and dynamic package inclusions.
 */
@AICore(sensitivity = "High", note = "Adaptive tail sampler and dynamic package filter controller")
@AIObservability(metrics = {"sampled_events_ratio", "active_package_filters_count"})
@AIPublicAPI(reason = "Dynamic sampling and runtime instrumentation configuration API")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.LOCK_FREE, note = "Thread-safe atomic sampling and concurrent package sets")
public class AdaptiveSampler {

    private final AtomicReference<Double> sampleRate = new AtomicReference<>(1.0);
    private final AtomicBoolean tailSamplingEnabled = new AtomicBoolean(true);
    private final AtomicReference<Double> slowTraceThresholdMs = new AtomicReference<>(50.0);
    private final Set<String> activePackages = ConcurrentHashMap.newKeySet();

    public AdaptiveSampler() {
    }

    public AdaptiveSampler(double initialSampleRate, String... initialPackages) {
        setSampleRate(initialSampleRate);
        for (String pkg : initialPackages) {
            addPackage(pkg);
        }
    }

    public boolean shouldSample(long durationNanos, String exceptionType) {
        boolean isError = !"NONE".equalsIgnoreCase(exceptionType);
        double durationMs = durationNanos / 1_000_000.0;

        // Tail sampling: always keep errors and slow transactions
        if (tailSamplingEnabled.get() && (isError || durationMs >= slowTraceThresholdMs.get())) {
            return true;
        }

        double rate = sampleRate.get();
        if (rate >= 1.0) return true;
        if (rate <= 0.0) return false;

        return ThreadLocalRandom.current().nextDouble() < rate;
    }

    /**
     * Decides the fate of a whole trace, once it is complete.
     *
     * <p>This is what tail sampling means. Deciding per span, as {@link #shouldSample} does, keeps a
     * random subset of each trace's spans at any rate below 1.0, so surviving children reference
     * parents that were dropped and the call graph silently loses those edges. At the default rate
     * of 1.0 nothing was visibly wrong, which is why it went unnoticed.
     *
     * @param maxDurationNs the longest span in the trace, which is the root unless a child overran
     * @param anyError      whether any span in the trace left by throwing
     */
    public boolean shouldSampleTrace(long maxDurationNs, boolean anyError) {
        double maxDurationMs = maxDurationNs / 1_000_000.0;

        // Keep every trace that is interesting, whatever the rate says.
        if (tailSamplingEnabled.get() && (anyError || maxDurationMs >= slowTraceThresholdMs.get())) {
            return true;
        }

        double rate = sampleRate.get();
        if (rate >= 1.0) return true;
        if (rate <= 0.0) return false;

        return ThreadLocalRandom.current().nextDouble() < rate;
    }

    public boolean isClassIncluded(String className) {
        if (activePackages.isEmpty()) {
            return true;
        }
        for (String pkg : activePackages) {
            if (className.startsWith(pkg)) {
                return true;
            }
        }
        return false;
    }

    public void addPackage(String pkgPrefix) {
        activePackages.add(pkgPrefix);
    }

    public void removePackage(String pkgPrefix) {
        activePackages.remove(pkgPrefix);
    }

    public Set<String> getActivePackages() {
        return Collections.unmodifiableSet(activePackages);
    }

    public double getSampleRate() {
        return sampleRate.get();
    }

    public void setSampleRate(double rate) {
        this.sampleRate.set(Math.max(0.0, Math.min(1.0, rate)));
    }

    public boolean isTailSamplingEnabled() {
        return tailSamplingEnabled.get();
    }

    public void setTailSamplingEnabled(boolean enabled) {
        this.tailSamplingEnabled.set(enabled);
    }

    public double getSlowTraceThresholdMs() {
        return slowTraceThresholdMs.get();
    }

    public void setSlowTraceThresholdMs(double thresholdMs) {
        this.slowTraceThresholdMs.set(Math.max(0.0, thresholdMs));
    }

    public String toJson() {
        return String.format(
                java.util.Locale.US,
                "{\"sampleRate\":%.2f,\"tailSampling\":%b,\"slowThresholdMs\":%.2f,\"packages\":[%s]}",
                sampleRate.get(),
                tailSamplingEnabled.get(),
                slowTraceThresholdMs.get(),
                String.join(",", activePackages.stream().map(p -> "\"" + p + "\"").toList())
        );
    }
}
