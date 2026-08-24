package nanometer.anomaly;

import nanometer.graph.GraphMetricAggregator;
import nanometer.model.RelationalMetricEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RootCauseAnalyzerTest {

    @Test
    public void testAnalyzeRootCauses() {
        GraphMetricAggregator aggregator = new GraphMetricAggregator();

        // Happy path: no findings
        List<RootCauseAnalyzer.RootCauseFinding> empty = RootCauseAnalyzer.analyze(aggregator);
        assertTrue(empty.isEmpty());
        String cleanMd = RootCauseAnalyzer.generateMarkdownDiagnosis(aggregator);
        assertTrue(cleanMd.contains("No runtime exceptions"));

        // Error path: placeOrder -> PaymentClient -> IllegalStateException
        aggregator.processEvent(new RelationalMetricEvent(
                1, 0, 1, "OrderService", "placeOrder", 20_000_000L, "NONE", System.currentTimeMillis()
        ));
        aggregator.processEvent(new RelationalMetricEvent(
                1, 1, 2, "PaymentClient", "charge", 10_000_000L, "IllegalStateException", System.currentTimeMillis()
        ));

        List<RootCauseAnalyzer.RootCauseFinding> findings = RootCauseAnalyzer.analyze(aggregator);
        assertFalse(findings.isEmpty());
        assertEquals("PaymentClient.charge", findings.get(0).failingComponent());
        assertEquals("IllegalStateException", findings.get(0).exceptionType());
        assertTrue(findings.get(0).toJson().contains("PaymentClient.charge"));

        String md = RootCauseAnalyzer.generateMarkdownDiagnosis(aggregator);
        assertTrue(md.contains("PaymentClient.charge"));
        assertTrue(md.contains("IllegalStateException"));
        assertTrue(md.contains("circuit breaker"));

        RootCauseAnalyzer analyzer = new RootCauseAnalyzer();
        assertNotNull(analyzer);
    }
}
