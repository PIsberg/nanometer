package nanometer.storage;

import nanometer.buffer.MetricRingBuffer;
import nanometer.graph.GraphMetricAggregator;
import nanometer.model.RelationalMetricEvent;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
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

    private final Connection connection;
    private final MetricRingBuffer ringBuffer;
    private final GraphMetricAggregator aggregator;
    private final ScheduledExecutorService scheduler;

    public MetricDatabaseFlusher(Connection connection, MetricRingBuffer ringBuffer, GraphMetricAggregator aggregator) {
        this.connection = connection;
        this.ringBuffer = ringBuffer;
        this.aggregator = aggregator;
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

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS execution_metrics (
                    timestamp INTEGER,
                    trace_id INTEGER,
                    parent_span_id INTEGER,
                    span_id INTEGER,
                    class_name TEXT,
                    method_name TEXT,
                    duration_ms REAL,
                    exception_type TEXT
                );
            """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_exec_ts ON execution_metrics(timestamp);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_exec_method ON execution_metrics(class_name, method_name);");
        } catch (SQLException e) {
            System.err.println("[Nanometer] Schema init error: " + e.getMessage());
        }
    }

    private void startWorker() {
        scheduler.scheduleAtFixedRate(this::flushBatch, 500, 500, TimeUnit.MILLISECONDS);
    }

    public synchronized void flushBatch() {
        List<RelationalMetricEvent> batch = new ArrayList<>();
        ringBuffer.drainTo(batch, 2000);

        if (batch.isEmpty()) {
            return;
        }

        // Process in-memory graph
        for (RelationalMetricEvent event : batch) {
            aggregator.processEvent(event);
        }

        // Write batch to SQLite
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement stmt = connection.prepareStatement("""
                INSERT INTO execution_metrics VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
                for (RelationalMetricEvent event : batch) {
                    stmt.setLong(1, event.timestamp());
                    stmt.setLong(2, event.traceId());
                    stmt.setLong(3, event.parentSpanId());
                    stmt.setLong(4, event.currentSpanId());
                    stmt.setString(5, event.className());
                    stmt.setString(6, event.methodName());
                    stmt.setDouble(7, event.durationMs());
                    stmt.setString(8, event.exceptionType());
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
