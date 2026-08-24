package nanometer.storage;

import nanometer.buffer.MetricRingBuffer;
import nanometer.graph.GraphMetricAggregator;
import nanometer.model.RelationalMetricEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

public class MetricQueryServiceTest {

    private Connection connection;
    private MetricDatabaseFlusher flusher;
    private MetricQueryService queryService;

    @BeforeEach
    public void setup() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        MetricRingBuffer buffer = MetricRingBuffer.createDefault();
        GraphMetricAggregator aggregator = new GraphMetricAggregator();
        flusher = new MetricDatabaseFlusher(connection, buffer, aggregator);
        queryService = new MetricQueryService(connection);

        buffer.offer(new RelationalMetricEvent(
                1, 0, 1, "QueryServiceTest", "execute", 12_000_000L, "NONE", System.currentTimeMillis()
        ));
        buffer.offer(new RelationalMetricEvent(
                1, 1, 2, "QueryServiceTest", "fail", 8_000_000L, "NullPointerException", System.currentTimeMillis()
        ));
        flusher.flushBatch();
    }

    @AfterEach
    public void teardown() throws Exception {
        flusher.shutdown();
    }

    @Test
    public void testSafeSelectQueries() {
        MetricQueryService.QueryResult res = queryService.executeQuery("SELECT class_name, method_name, count(*) as c FROM execution_metrics GROUP BY class_name, method_name;");
        assertNotNull(res);
        assertNull(res.error());
        assertEquals(3, res.columns().size());
        assertEquals(2, res.rows().size());

        String json = res.toJson();
        assertNotNull(json);
        assertTrue(json.contains("QueryServiceTest"));
        assertTrue(json.contains("executionTimeMs"));
    }

    @Test
    public void testRejectMutatingQueries() {
        MetricQueryService.QueryResult res = queryService.executeQuery("DROP TABLE metrics;");
        assertNotNull(res);
        assertNotNull(res.error());
        assertTrue(res.error().contains("Security violation"));
    }

    @Test
    public void testCannedQueries() {
        assertNotNull(MetricQueryService.getTopSlowestQuery());
        assertNotNull(MetricQueryService.getExceptionBreakdownQuery());
        assertNotNull(MetricQueryService.getThroughputTimelineQuery());

        MetricQueryService.QueryResult res = queryService.executeQuery(MetricQueryService.getTopSlowestQuery());
        assertNotNull(res);
        assertNull(res.error());
    }
}
