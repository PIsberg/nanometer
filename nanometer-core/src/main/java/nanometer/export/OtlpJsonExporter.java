package nanometer.export;

import nanometer.model.RelationalMetricEvent;
import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenTelemetry Protocol (OTLP) JSON exporter serializing RelationalMetricEvents into standard OTLP spans.
 */
@AICore(sensitivity = "High", note = "OpenTelemetry Protocol (OTLP) JSON serializing and HTTP exporter")
@AIObservability(metrics = {"otlp_exported_spans_total", "otlp_export_duration_ms"})
@AIPublicAPI(reason = "OTLP span serialization and distributed tracing telemetry export")
public class OtlpJsonExporter {

    public static String exportToJson(String serviceName, List<RelationalMetricEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"resourceSpans\":[{\"resource\":{\"attributes\":[");
        sb.append("{\"key\":\"service.name\",\"value\":{\"stringValue\":\"").append(escapeJson(serviceName)).append("\"}},");
        sb.append("{\"key\":\"telemetry.sdk.name\",\"value\":{\"stringValue\":\"nanometer\"}}");
        sb.append("]},\"scopeSpans\":[{\"scope\":{\"name\":\"io.nanometer\"},\"spans\":[");

        List<String> spans = new ArrayList<>();
        for (RelationalMetricEvent e : events) {
            long startNanos = e.timestamp() * 1_000_000L;
            long endNanos = startNanos + e.durationNs();
            String traceIdHex = String.format("%032x", e.traceId());
            String spanIdHex = String.format("%016x", e.currentSpanId());
            String parentSpanIdHex = e.parentSpanId() > 0 ? String.format("%016x", e.parentSpanId()) : "";
            boolean isErr = !"NONE".equalsIgnoreCase(e.exceptionType());

            StringBuilder span = new StringBuilder();
            span.append("{");
            span.append("\"traceId\":\"").append(traceIdHex).append("\",");
            span.append("\"spanId\":\"").append(spanIdHex).append("\",");
            if (!parentSpanIdHex.isEmpty()) {
                span.append("\"parentSpanId\":\"").append(parentSpanIdHex).append("\",");
            }
            span.append("\"name\":\"").append(escapeJson(e.className() + "." + e.methodName())).append("\",");
            span.append("\"kind\":1,"); // SPAN_KIND_INTERNAL
            span.append("\"startTimeUnixNano\":").append(startNanos).append(",");
            span.append("\"endTimeUnixNano\":").append(endNanos).append(",");
            span.append("\"attributes\":[");
            span.append("{\"key\":\"code.namespace\",\"value\":{\"stringValue\":\"").append(escapeJson(e.className())).append("\"}},");
            span.append("{\"key\":\"code.function\",\"value\":{\"stringValue\":\"").append(escapeJson(e.methodName())).append("\"}}");
            if (isErr) {
                span.append(",{\"key\":\"exception.type\",\"value\":{\"stringValue\":\"").append(escapeJson(e.exceptionType())).append("\"}}");
            }
            span.append("],");
            span.append("\"status\":{\"code\":").append(isErr ? 2 : 1).append("}"); // 2 = STATUS_CODE_ERROR, 1 = STATUS_CODE_OK
            span.append("}");

            spans.add(span.toString());
        }

        sb.append(String.join(",", spans));
        sb.append("]}]}]}");
        return sb.toString();
    }

    public static boolean sendToOtlpEndpoint(String endpointUrl, String serviceName, List<RelationalMetricEvent> events) {
        if (events.isEmpty()) {
            return true;
        }
        try {
            String payload = exportToJson(serviceName, events);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private static String escapeJson(@Nullable String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
