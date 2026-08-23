package nanometer.discovery;

import nanometer.graph.GraphMetricAggregator;
import nanometer.model.RelationalMetricEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GraphAutoDiscoveryEngineTest {

    @Test
    public void testDiscoveryEngineWithAggregator() {
        GraphMetricAggregator aggregator = new GraphMetricAggregator();

        // 1. Empty discover
        String emptyJson = GraphAutoDiscoveryEngine.generateGraphJson(aggregator);
        assertNotNull(emptyJson);
        assertTrue(emptyJson.contains("\"nodes\": []"));
        assertTrue(emptyJson.contains("\"edges\": []"));

        // 2. Populated discover
        aggregator.processEvent(new RelationalMetricEvent(
                1, 0, 1, "ServiceA", "action", 10_000_000L, "NONE", System.currentTimeMillis()
        ));
        aggregator.processEvent(new RelationalMetricEvent(
                1, 1, 2, "ServiceB", "fetch", 5_000_000L, "RuntimeException", System.currentTimeMillis()
        ));

        String json = GraphAutoDiscoveryEngine.generateGraphJson(aggregator);
        assertNotNull(json);
        assertTrue(json.contains("ServiceA.action"));
        assertTrue(json.contains("ServiceB.fetch"));
        assertTrue(json.contains("Exception.RuntimeException"));
        assertTrue(json.contains("\"isException\":true"));

        // 3. Chart specs
        List<GraphAutoDiscoveryEngine.ChartSpec> specs = GraphAutoDiscoveryEngine.getDiscoveredCharts();
        assertEquals(4, specs.size());
        assertEquals("LINE", specs.get(0).chartType());

        // 4. Instantiation & escapeJson
        GraphAutoDiscoveryEngine engine = new GraphAutoDiscoveryEngine();
        assertNotNull(engine);
    }
}
