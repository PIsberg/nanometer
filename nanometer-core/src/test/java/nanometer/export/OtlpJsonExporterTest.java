package nanometer.export;

import nanometer.model.RelationalMetricEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class OtlpJsonExporterTest {

    @Test
    public void testExportToJson() {
        RelationalMetricEvent e1 = new RelationalMetricEvent(
                12345L, 0L, 67890L, "OrderService", "placeOrder", 25_000_000L, "NONE", System.currentTimeMillis()
        );
        RelationalMetricEvent e2 = new RelationalMetricEvent(
                12345L, 67890L, 99999L, "PaymentClient", "charge", 15_000_000L, "IllegalStateException", System.currentTimeMillis()
        );

        String json = OtlpJsonExporter.exportToJson("order-service", List.of(e1, e2));
        assertNotNull(json);
        assertTrue(json.contains("resourceSpans"));
        assertTrue(json.contains("order-service"));
        assertTrue(json.contains("OrderService.placeOrder"));
        assertTrue(json.contains("PaymentClient.charge"));
        assertTrue(json.contains("IllegalStateException"));

        // Empty events
        String emptyJson = OtlpJsonExporter.exportToJson("test-svc", List.of());
        assertNotNull(emptyJson);
        assertTrue(emptyJson.contains("\"spans\":[]"));

        // HTTP export with invalid URL returns false safely
        assertFalse(OtlpJsonExporter.sendToOtlpEndpoint("http://localhost:1", "test-svc", List.of(e1)));
        assertTrue(OtlpJsonExporter.sendToOtlpEndpoint("http://localhost:1", "test-svc", List.of()));

        OtlpJsonExporter exporter = new OtlpJsonExporter();
        assertNotNull(exporter);
    }
}
