package nanometer.anomaly;

import nanometer.model.RelationalMetricEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AnomalyDetectorTest {

    @Test
    public void testDetectAnomalies() {
        AnomalyDetector detector = new AnomalyDetector(2.0);

        // Train baseline with 20 executions of 10ms
        for (int i = 0; i < 20; i++) {
            detector.evaluate(new RelationalMetricEvent(
                    1, 0, 1, "PaymentService", "charge", 10_000_000L + (i % 3) * 1_000_000L, "NONE", System.currentTimeMillis()
            ));
        }

        // Send a massive 500ms outlier
        AnomalyDetector.AnomalyEvent anomaly = detector.evaluate(new RelationalMetricEvent(
                1, 0, 1, "PaymentService", "charge", 500_000_000L, "NONE", System.currentTimeMillis()
        ));

        assertNotNull(anomaly);
        assertEquals("PaymentService.charge", anomaly.methodKey());
        assertTrue(anomaly.sigmaScore() >= 2.0);

        List<AnomalyDetector.AnomalyEvent> recent = detector.getRecentAnomalies();
        assertFalse(recent.isEmpty());

        String json = detector.getAnomaliesJson();
        assertTrue(json.contains("PaymentService.charge"));
        assertTrue(json.contains("sigma"));
    }
}
