package nanometer.system;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;

/**
 * System and JVM telemetry sampler measuring CPU load, Heap & Non-Heap Memory, thread counts, and uptime.
 */
@AICore(sensitivity = "High", note = "JVM runtime telemetry and hardware utilization sampler")
@AIObservability(metrics = {"cpu_process_percent", "cpu_system_percent", "heap_used_mb", "thread_count"})
@AIPublicAPI(reason = "Public interface for capturing instant JVM & OS resource utilization snapshots")
public class SystemMetricsSampler {

    public record SystemSnapshot(
            double processCpuPercent,
            double systemCpuPercent,
            long heapUsedMb,
            long heapMaxMb,
            long nonHeapUsedMb,
            int threadCount,
            long uptimeMs,
            int availableProcessors
    ) {
        public String toJson() {
            return String.format(
                    java.util.Locale.US,
                    "{\"processCpu\":%.2f,\"systemCpu\":%.2f,\"heapUsedMb\":%d,\"heapMaxMb\":%d,\"nonHeapUsedMb\":%d,\"threadCount\":%d,\"uptimeMs\":%d,\"processors\":%d}",
                    processCpuPercent,
                    systemCpuPercent,
                    heapUsedMb,
                    heapMaxMb,
                    nonHeapUsedMb,
                    threadCount,
                    uptimeMs,
                    availableProcessors
            );
        }
    }

    public static SystemSnapshot captureSnapshot() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

        double processCpu = 0.0;
        double systemCpu = 0.0;

        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            double pLoad = sunOs.getProcessCpuLoad();
            double sLoad = sunOs.getCpuLoad();
            if (pLoad >= 0) {
                processCpu = pLoad * 100.0;
            }
            if (sLoad >= 0) {
                systemCpu = sLoad * 100.0;
            }
        }

        long heapUsed = memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);
        long heapMax = memoryBean.getHeapMemoryUsage().getMax() / (1024 * 1024);
        if (heapMax <= 0) {
            heapMax = memoryBean.getHeapMemoryUsage().getCommitted() / (1024 * 1024);
        }
        long nonHeapUsed = memoryBean.getNonHeapMemoryUsage().getUsed() / (1024 * 1024);
        int threads = threadBean.getThreadCount();
        long uptime = runtimeBean.getUptime();
        int processors = osBean.getAvailableProcessors();

        return new SystemSnapshot(
                processCpu,
                systemCpu,
                heapUsed,
                heapMax,
                nonHeapUsed,
                threads,
                uptime,
                processors
        );
    }
}
