package nanometer.storage;

import nanometer.buffer.MetricRingBuffer;
import nanometer.graph.GraphMetricAggregator;
import nanometer.model.RelationalMetricEvent;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

public class MetricDatabaseFlusherTest {

    @Test
    public void testDatabaseFlusherLifecycleAndBatches() throws Exception {
        File dbFile = new File("target/test_metrics.db");
        if (dbFile.exists()) {
            dbFile.delete();
        }

        Connection connection = DriverManager.getConnection("jdbc:sqlite:target/test_metrics.db");
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
}
