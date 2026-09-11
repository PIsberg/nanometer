package nanometer;

import nanometer.agent.AutoMetricInterceptor;
import nanometer.agent.NanometerAgent;
import nanometer.anomaly.AnomalyDetector;
import nanometer.buffer.MetricRingBuffer;
import nanometer.graph.GraphMetricAggregator;
import nanometer.sampling.AdaptiveSampler;
import nanometer.server.NanometerVisualizerServer;
import nanometer.storage.MetricDatabaseFlusher;
import nanometer.storage.MetricQueryService;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Public bootstrap API for embedding Nanometer into Java applications and libraries.
 */
@AICore(sensitivity = "High", note = "Main entrypoint and lifecycle manager for Nanometer embedded APM")
@AIPublicAPI(reason = "Public entrypoint for embedding Nanometer in host applications and libraries")
@AIObservability(metrics = {"execution_duration_ms", "call_count"}, traces = {"traceId", "spanId"})
@AIThreadSafe(strategy = AIThreadSafe.Strategy.SYNCHRONIZED, note = "Thread-safe singleton lifecycle methods")
public class Nanometer {

    private static volatile boolean initialized = false;
    private static volatile @Nullable MetricRingBuffer ringBuffer;
    private static volatile @Nullable GraphMetricAggregator graphAggregator;
    private static volatile @Nullable MetricDatabaseFlusher dbFlusher;
    private static volatile @Nullable MetricQueryService queryService;
    private static volatile @Nullable NanometerVisualizerServer visualizerServer;

    /**
     * Installs the agent for a package prefix and opens the local metrics store.
     *
     * @param packagePrefix the package to instrument, for example {@code com.example.order}. Must
     *                      not be blank: instrumenting every loaded class records a span for every
     *                      call in the process, including its dependencies, which the ring buffer
     *                      cannot absorb, so the dominant behaviour becomes silent shedding.
     * @throws IllegalArgumentException if {@code packagePrefix} is blank
     * @throws IllegalStateException    if the agent cannot attach or the store cannot be opened.
     *                                  This used to be caught, printed and followed by marking the
     *                                  install successful, so a failed attach was indistinguishable
     *                                  from a working one.
     */
    public static synchronized void install(String packagePrefix) {
        if (initialized) {
            return;
        }
        if (packagePrefix == null || packagePrefix.isBlank()) {
            throw new IllegalArgumentException(
                    "packagePrefix must name the package to instrument, for example \"com.example\". "
                            + "Instrumenting everything is never what an embedded profiler should do by default.");
        }

        MetricRingBuffer buffer = MetricRingBuffer.createDefault();
        ringBuffer = buffer;
        AutoMetricInterceptor.setBuffer(buffer);
        GraphMetricAggregator aggregator = new GraphMetricAggregator();
        graphAggregator = aggregator;

        try {
            // Dynamic ByteBuddy Agent installation
            Instrumentation inst = ByteBuddyAgent.install();
            NanometerAgent.install(packagePrefix, inst);

            // Embedded SQLite storage engine. The writer and the reader get separate connections:
            // a JDBC connection is not safe for concurrent use, the flusher toggles auto-commit on
            // its own, and WAL only buys concurrent readers when the connections are distinct.
            File dbFile = new File(".nanometer/metrics.db");
            File parent = dbFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            String path = dbFile.getAbsolutePath();
            Connection writeConnection = DriverManager.getConnection("jdbc:sqlite:" + path);
            dbFlusher = new MetricDatabaseFlusher(writeConnection, buffer, aggregator);
            queryService = new MetricQueryService(MetricQueryService.openReadOnly(path));

            initialized = true;
        } catch (Exception e) {
            throw new IllegalStateException("Nanometer failed to install: " + e.getMessage(), e);
        }
    }

    public static synchronized void startVisualizer(int port) {
        if (visualizerServer == null && graphAggregator != null) {
            NanometerVisualizerServer server = new NanometerVisualizerServer(
                    port,
                    graphAggregator,
                    dbFlusher,
                    queryService,
                    new AnomalyDetector(),
                    AutoMetricInterceptor.getProfileSampler(),
                    AutoMetricInterceptor.getSampler()
            );
            server.start();
            visualizerServer = server;
        }
    }

    /**
     * Token the visualizer requires on its requests, or {@code null} if it is not running. An
     * embedder needs this to build the dashboard URL itself rather than reading it off the console.
     */
    public static @Nullable String getVisualizerToken() {
        NanometerVisualizerServer server = visualizerServer;
        return server != null ? server.getAuthToken() : null;
    }

    public static @Nullable MetricRingBuffer getBuffer() {
        return ringBuffer;
    }

    public static @Nullable GraphMetricAggregator getGraphAggregator() {
        return graphAggregator;
    }

    public static @Nullable MetricDatabaseFlusher getDbFlusher() {
        return dbFlusher;
    }

    public static @Nullable MetricQueryService getQueryService() {
        return queryService;
    }

    public static AdaptiveSampler getSampler() {
        return AutoMetricInterceptor.getSampler();
    }

    public static synchronized void shutdown() {
        if (visualizerServer != null) {
            visualizerServer.stop();
            visualizerServer = null;
        }
        if (dbFlusher != null) {
            dbFlusher.shutdown();
            dbFlusher = null;
        }
        if (queryService != null) {
            // The reader owns its own connection now, so shutting down the flusher no longer closes
            // it as a side effect.
            queryService.close();
            queryService = null;
        }
        ringBuffer = null;
        graphAggregator = null;
        initialized = false;
    }
}
