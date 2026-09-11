package nanometer.storage;

import nanometer.buffer.MetricRingBuffer;
import nanometer.graph.GraphMetricAggregator;
import nanometer.model.RelationalMetricEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

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
        assertTrue(res.error().contains("Only SELECT"), "was: " + res.error());
    }

    @Test
    public void pragmaIsNoLongerWavedThroughAsAReadQuery() {
        // PRAGMA writes. journal_mode, user_version and writable_schema all mutate the database,
        // so allowing it while claiming a read-only service was a false guarantee.
        for (String sql : new String[]{
                "PRAGMA user_version=99;",
                "pragma writable_schema=ON;",
                "PRAGMA journal_mode=DELETE;"}) {
            MetricQueryService.QueryResult res = queryService.executeQuery(sql);
            assertNotNull(res.error(), sql + " was accepted");
            assertTrue(res.error().contains("Only SELECT"), sql + " gave: " + res.error());
        }
    }

    @Test
    public void aReadOnlyConnectionRefusesWritesThatSlipPastThePrefixCheck() throws Exception {
        Path db = Files.createTempDirectory("nanometer-ro").resolve("metrics.db");

        // Write something through a normal connection first.
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            MetricRingBuffer buffer = new MetricRingBuffer(16);
            MetricDatabaseFlusher writeFlusher = new MetricDatabaseFlusher(
                    writer, buffer, new GraphMetricAggregator());
            buffer.offer(new RelationalMetricEvent(
                    0L, 1L, 0L, 1L, "Svc", "call", "", "", 1_000_000L, "NONE",
                    System.currentTimeMillis()));
            writeFlusher.flushBatch();
            writeFlusher.shutdown();
        }

        Connection readOnly = MetricQueryService.openReadOnly(db.toString());
        try {
            // The boundary is SQLite's, not a string check: a write fails even when issued directly.
            SQLException refused = assertThrows(SQLException.class, () -> {
                try (Statement stmt = readOnly.createStatement()) {
                    stmt.executeUpdate("DELETE FROM execution_metrics");
                }
            });
            assertTrue(refused.getMessage().toLowerCase(Locale.ROOT).contains("read"),
                    "expected a read-only complaint, got: " + refused.getMessage());

            // Reads still work.
            MetricQueryService reader = new MetricQueryService(readOnly);
            MetricQueryService.QueryResult res = reader.executeQuery(
                    "SELECT count(*) AS c FROM execution_metrics");
            assertNull(res.error(), "read failed: " + res.error());
            assertEquals("1", res.rows().get(0).get(0));
        } finally {
            readOnly.close();
        }
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
