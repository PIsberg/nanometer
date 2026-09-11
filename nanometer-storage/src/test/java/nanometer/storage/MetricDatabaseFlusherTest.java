package nanometer.storage;

import nanometer.buffer.MetricRingBuffer;
import nanometer.graph.GraphMetricAggregator;
import nanometer.model.RelationalMetricEvent;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class MetricDatabaseFlusherTest {

    @Test
    public void testDatabaseFlusherLifecycleAndBatches() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        MetricRingBuffer buffer = new MetricRingBuffer(16);
        GraphMetricAggregator aggregator = new GraphMetricAggregator();

        // 1. Initialize flusher with connection
        MetricDatabaseFlusher flusher = new MetricDatabaseFlusher(
                connection, buffer, aggregator
        );

        // 2. Empty flush
        flusher.flushBatch();

        // 3. Populate buffer and flush
        buffer.offer(new RelationalMetricEvent(
                1, 0, 1, "TestClass", "testMethod", 12_000_000L, "NONE", System.currentTimeMillis()
        ));
        buffer.offer(new RelationalMetricEvent(
                1, 1, 2, "TestClass", "failMethod", 8_000_000L, "IllegalArgumentException", System.currentTimeMillis()
        ));

        assertEquals(2, buffer.size());
        flusher.flushBatch();
        assertEquals(0, buffer.size());

        // Verify aggregator updated
        GraphMetricAggregator.NodeKey key1 = new GraphMetricAggregator.NodeKey("TestClass", "testMethod");
        GraphMetricAggregator.NodeKey key2 = new GraphMetricAggregator.NodeKey("TestClass", "failMethod");
        assertTrue(aggregator.getNodeMetrics().containsKey(key1));
        assertTrue(aggregator.getNodeMetrics().containsKey(key2));

        // 4. Shutdown flusher
        flusher.shutdown();
        // Double shutdown should be safe
        flusher.shutdown();
        connection.close();
    }

    @Test
    public void retentionRemovesRowsOlderThanTheWindowAndKeepsTheRest() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        MetricRingBuffer buffer = new MetricRingBuffer(16);
        MetricDatabaseFlusher flusher = new MetricDatabaseFlusher(
                connection, buffer, new GraphMetricAggregator(), Duration.ofMinutes(30));

        long now = System.currentTimeMillis();
        buffer.offer(event("fresh", now - Duration.ofMinutes(5).toMillis()));
        buffer.offer(event("stale", now - Duration.ofHours(3).toMillis()));
        flusher.flushBatch();
        assertEquals(2, rowCount(connection), "both rows are written before pruning");

        flusher.pruneExpiredRows();

        assertEquals(1, rowCount(connection),
                "without retention this table grows for the life of the host process");
        assertEquals("fresh", singleMethodName(connection));

        flusher.shutdown();
    }

    @Test
    public void aDatabaseFromAnOlderSchemaIsRebuiltRatherThanSilentlyReused() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement stmt = connection.createStatement()) {
            // The shape this project wrote before the trace id widened and the timestamp moved.
            stmt.execute("CREATE TABLE execution_metrics (timestamp INTEGER, trace_id INTEGER);");
            stmt.execute("INSERT INTO execution_metrics VALUES (1, 1);");
            stmt.execute("PRAGMA user_version=1;");
        }

        MetricDatabaseFlusher flusher = new MetricDatabaseFlusher(
                connection, new MetricRingBuffer(16), new GraphMetricAggregator());

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version;")) {
            assertTrue(rs.next());
            assertEquals(MetricDatabaseFlusher.SCHEMA_VERSION, rs.getInt(1),
                    "the version marker must be updated, or the next start repeats the rebuild");
        }
        assertEquals(0, rowCount(connection), "rows from the incompatible schema are discarded");

        // The new columns must actually be usable.
        MetricRingBuffer buffer = new MetricRingBuffer(16);
        MetricDatabaseFlusher writer = new MetricDatabaseFlusher(
                connection, buffer, new GraphMetricAggregator());
        buffer.offer(event("afterRebuild", System.currentTimeMillis()));
        writer.flushBatch();
        assertEquals(1, rowCount(connection));
        assertEquals("afterRebuild", singleMethodName(connection));

        writer.shutdown();
        flusher.shutdown();
    }

    private static RelationalMetricEvent event(String methodName, long startTimestamp) {
        return new RelationalMetricEvent(
                0L, 7L, 0L, 1L, "TestClass", methodName, "", "",
                1_000_000L, "NONE", startTimestamp);
    }

    private static int rowCount(Connection connection) throws Exception {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM execution_metrics")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String singleMethodName(Connection connection) throws Exception {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT method_name FROM execution_metrics")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
