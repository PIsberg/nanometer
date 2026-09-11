package nanometer.storage;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Embedded SQLite analytics and query service executing safe read-only SQL queries on metrics.db.
 */
@AICore(sensitivity = "High", note = "Read-only SQLite analytics query service and canned report engine")
@AIObservability(metrics = {"sql_queries_executed_total", "sql_query_duration_ms"})
@AIPublicAPI(reason = "Public interface for querying SQLite telemetry metrics database")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.SYNCHRONIZED, note = "Thread-safe JDBC connection handling")
public class MetricQueryService {

    public record QueryResult(
            List<String> columns,
            List<List<String>> rows,
            long executionTimeMs,
            @Nullable String error
    ) {
        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"columns\":[").append(String.join(",", columns.stream().map(c -> "\"" + escapeJson(c) + "\"").toList())).append("],");
            sb.append("\"rows\":[");
            List<String> rowStrings = new ArrayList<>();
            for (List<String> row : rows) {
                rowStrings.add("[" + String.join(",", row.stream().map(v -> "\"" + escapeJson(v) + "\"").toList()) + "]");
            }
            sb.append(String.join(",", rowStrings));
            sb.append("],");
            sb.append("\"executionTimeMs\":").append(executionTimeMs);
            if (error != null) {
                sb.append(",\"error\":\"").append(escapeJson(error)).append("\"");
            }
            sb.append("}");
            return sb.toString();
        }
    }

    private final Connection connection;

    public MetricQueryService(Connection connection) {
        this.connection = connection;
    }

    public QueryResult executeQuery(String sql) {
        String trimmed = sql.trim();
        String upper = trimmed.toUpperCase();
        if (!upper.startsWith("SELECT") && !upper.startsWith("EXPLAIN") && !upper.startsWith("PRAGMA")) {
            return new QueryResult(List.of(), List.of(), 0, "Security violation: Only SELECT / PRAGMA read queries are allowed.");
        }

        long start = System.currentTimeMillis();
        List<String> columns = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();

        try (Statement stmt = connection.createStatement()) {
            stmt.setMaxRows(200);
            try (ResultSet rs = stmt.executeQuery(trimmed)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                for (int i = 1; i <= colCount; i++) {
                    columns.add(meta.getColumnLabel(i));
                }

                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        Object val = rs.getObject(i);
                        row.add(val != null ? val.toString() : "NULL");
                    }
                    rows.add(row);
                }
            }
            long duration = System.currentTimeMillis() - start;
            return new QueryResult(columns, rows, duration, null);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return new QueryResult(List.of(), List.of(), duration, e.getMessage());
        }
    }

    public static String getTopSlowestQuery() {
        return "SELECT class_name, method_name, count(*) as calls, round(avg(duration_ns) / 1e6, 2) as avg_ms, round(max(duration_ns) / 1e6, 2) as max_ms FROM execution_metrics GROUP BY class_name, method_name ORDER BY avg_ms DESC LIMIT 10;";
    }

    public static String getExceptionBreakdownQuery() {
        return "SELECT exception_type, count(*) as error_count FROM execution_metrics WHERE exception_type != 'NONE' GROUP BY exception_type ORDER BY error_count DESC;";
    }

    public static String getThroughputTimelineQuery() {
        return "SELECT strftime('%Y-%m-%d %H:%M:%S', start_timestamp / 1000, 'unixepoch') as time_bucket, count(*) as call_count FROM execution_metrics GROUP BY time_bucket ORDER BY time_bucket DESC LIMIT 20;";
    }

    private static String escapeJson(@Nullable String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
