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

    public static synchronized void install(String packagePrefix) {
        if (initialized) {
            return;
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

            // Embedded SQLite storage engine
            File dbFile = new File(".nanometer/metrics.db");
            File parent = dbFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            queryService = new MetricQueryService(conn);
            dbFlusher = new MetricDatabaseFlusher(conn, buffer, aggregator);

            initialized = true;
        } catch (Exception e) {
            System.err.println("[Nanometer] Dynamic attach warning: " + e.getMessage());
            initialized = true;
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
        queryService = null;
        initialized = false;
    }
}
