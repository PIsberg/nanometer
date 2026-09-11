package nanometer.storage;

import nanometer.anomaly.AnomalyDetector;
import nanometer.buffer.MetricRingBuffer;
import nanometer.graph.GraphMetricAggregator;
import nanometer.model.RelationalMetricEvent;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background batch flusher persisting events to an embedded local database (SQLite/WAL).
 */
@AICore(sensitivity = "High", note = "Background worker managing WAL mode SQLite transaction batches")
@AIObservability(metrics = {"db_batch_drain_count", "db_write_latency_ms"})
@AIThreadSafe(strategy = AIThreadSafe.Strategy.SYNCHRONIZED, note = "Thread-safe batch draining with single-thread scheduler")
public class MetricDatabaseFlusher {

    /** Bumped whenever the execution_metrics columns change. */
    static final int SCHEMA_VERSION = 2;

    /** How long rows are kept before the retention job removes them. */
    public static final Duration DEFAULT_RETENTION = Duration.ofHours(6);

    /** How many recently flushed events stay available for on-demand OTLP serialisation. */
    private static final int RECENT_EVENT_CAPACITY = 500;

    private final Connection connection;
    private final MetricRingBuffer ringBuffer;
    private final GraphMetricAggregator aggregator;
    private final ScheduledExecutorService scheduler;
    private final Duration retention;

    /**
     * Consumers that used to be constructed and then never fed. The drain loop is the one place
     * every event passes through, so this is where they belong.
     */
    private final @Nullable AnomalyDetector anomalyDetector;
    private final @Nullable OtlpSink otlpSink;

    private final ArrayDeque<RelationalMetricEvent> recentEvents = new ArrayDeque<>();

    public MetricDatabaseFlusher(Connection connection, MetricRingBuffer ringBuffer, GraphMetricAggregator aggregator) {
        this(connection, ringBuffer, aggregator, DEFAULT_RETENTION);
    }

    public MetricDatabaseFlusher(Connection connection,
                                 MetricRingBuffer ringBuffer,
                                 GraphMetricAggregator aggregator,
                                 Duration retention) {
        this(connection, ringBuffer, aggregator, retention, null, null);
    }

    /**
     * @param anomalyDetector fed every drained event, or {@code null} to skip detection
     * @param otlpSink        fed every drained event, or {@code null} for no export
     */
    public MetricDatabaseFlusher(Connection connection,
                                 MetricRingBuffer ringBuffer,
                                 GraphMetricAggregator aggregator,
                                 Duration retention,
                                 @Nullable AnomalyDetector anomalyDetector,
                                 @Nullable OtlpSink otlpSink) {
        this.connection = connection;
        this.ringBuffer = ringBuffer;
        this.aggregator = aggregator;
        this.retention = retention;
        this.anomalyDetector = anomalyDetector;
        this.otlpSink = otlpSink;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nanometer-db-flusher");
            t.setDaemon(true);
            return t;
        });

        initSchema();
        startWorker();
    }

    private void initSchema() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA synchronous=NORMAL;");

            // CREATE TABLE IF NOT EXISTS silently accepts a database written by an older schema,
            // so the version is checked explicitly. This store is a rolling telemetry cache with a
            // retention window, not a system of record, so a mismatch is resolved by recreating the
            // table rather than by writing a migration for data that is about to expire anyway.
            int found = readSchemaVersion(stmt);
            if (found != 0 && found != SCHEMA_VERSION) {
                System.err.println("[Nanometer] metrics.db is schema v" + found + ", this build writes v"
                        + SCHEMA_VERSION + "; recreating execution_metrics and discarding its rows");
                stmt.execute("DROP TABLE IF EXISTS execution_metrics;");
            }

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS execution_metrics (
                    start_timestamp INTEGER,
                    trace_id TEXT,
                    parent_span_id INTEGER,
                    span_id INTEGER,
                    class_name TEXT,
                    method_name TEXT,
                    parent_class_name TEXT,
                    parent_method_name TEXT,
                    duration_ns INTEGER,
                    exception_type TEXT
                );
            """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_exec_ts ON execution_metrics(start_timestamp);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_exec_method ON execution_metrics(class_name, method_name);");
            stmt.execute("PRAGMA user_version=" + SCHEMA_VERSION + ";");
        } catch (SQLException e) {
            System.err.println("[Nanometer] Schema init error: " + e.getMessage());
        }
    }

    private static int readSchemaVersion(Statement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("PRAGMA user_version;")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Deletes rows outside the retention window. Without this the table grows for as long as the
     * host process runs; at ten thousand spans a second that is roughly 36 million rows an hour,
     * written into the host application working directory.
     */
    synchronized void pruneExpiredRows() {
        long cutoff = System.currentTimeMillis() - retention.toMillis();
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM execution_metrics WHERE start_timestamp < ?")) {
            stmt.setLong(1, cutoff);
            int deleted = stmt.executeUpdate();
            if (deleted > 0 && !connection.getAutoCommit()) {
                connection.commit();
            }
        } catch (SQLException e) {
            System.err.println("[Nanometer] Retention prune error: " + e.getMessage());
        }
    }

    private void startWorker() {
        scheduler.scheduleAtFixedRate(this::flushBatch, 500, 500, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::pruneExpiredRows, 1, 1, TimeUnit.MINUTES);
    }

    public synchronized void flushBatch() {
        List<RelationalMetricEvent> batch = new ArrayList<>();
        ringBuffer.drainTo(batch, 2000);

        if (batch.isEmpty()) {
            return;
        }

        // Process in-memory graph, and feed the consumers that were previously never called.
        for (RelationalMetricEvent event : batch) {
            aggregator.processEvent(event);
            if (anomalyDetector != null) {
                anomalyDetector.evaluate(event);
            }
            rememberRecent(event);
        }
        if (otlpSink != null) {
            otlpSink.accept(batch);
        }

        // Write batch to SQLite
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement stmt = connection.prepareStatement("""
                INSERT INTO execution_metrics VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
                for (RelationalMetricEvent event : batch) {
                    stmt.setLong(1, event.startTimestamp());
                    stmt.setString(2, event.traceIdHex());
                    stmt.setLong(3, event.parentSpanId());
                    stmt.setLong(4, event.currentSpanId());
                    stmt.setString(5, event.className());
                    stmt.setString(6, event.methodName());
                    stmt.setString(7, event.parentClassName());
                    stmt.setString(8, event.parentMethodName());
                    stmt.setLong(9, event.durationNs());
                    stmt.setString(10, event.exceptionType());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // Rollback exception suppressed
            }
            System.err.println("[Nanometer] DB flush error: " + e.getMessage());
        }
    }

    /** Events waiting in the ring buffer right now. */
    public int getBufferDepth() {
        return ringBuffer.size();
    }

    /**
     * Events the ring buffer shed because it was full. Counted since the beginning but displayed
     * nowhere, so a tool quietly discarding telemetry still presented a confident dashboard.
     */
    public long getDroppedEventCount() {
        return ringBuffer.getDroppedCount();
    }

    private synchronized void rememberRecent(RelationalMetricEvent event) {
        if (recentEvents.size() == RECENT_EVENT_CAPACITY) {
            recentEvents.removeFirst();
        }
        recentEvents.addLast(event);
    }

    /**
     * The most recently flushed events, oldest first. Bounded, so this cannot become a second
     * unbounded retention of every span. Exists so the OTLP endpoint can serialise real spans:
     * it previously hardcoded an empty list and always returned an envelope with none.
     */
    public synchronized List<RelationalMetricEvent> recentEvents() {
        return List.copyOf(recentEvents);
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        flushBatch();
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // Connection close exception suppressed
        }
    }
}
