package nanometer.storage;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Embedded SQLite analytics and query service executing safe read-only SQL queries on metrics.db.
 */
@AIObservability(metrics = {"executionTimeMs"})
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

    /**
     * @param connection a read-only connection. The caller is responsible for opening it read-only;
     *                   {@link #openReadOnly(String)} does that. Sharing the writer's connection
     *                   also breaks correctness independently of permissions: a JDBC connection is
     *                   not safe for concurrent use, and the flusher toggles auto-commit on it.
     */
    public MetricQueryService(Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens a connection SQLite itself refuses writes on, so the read path cannot modify the
     * database whatever SQL reaches it. A separate connection is also what makes the WAL journal
     * mode the flusher sets actually useful: one writer concurrent with many readers requires
     * distinct connections.
     */
    public static Connection openReadOnly(String databasePath) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:file:" + databasePath + "?mode=ro", readOnlyProperties());
    }

    private static Properties readOnlyProperties() {
        Properties props = new Properties();
        props.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY
        return props;
    }

    public QueryResult executeQuery(String sql) {
        String trimmed = sql.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);

        // A prefix test is a friendly early error, not a security boundary. PRAGMA in particular
        // writes: journal_mode, user_version and writable_schema all mutate the database, the last
        // of them the schema itself. The boundary that actually holds is the read-only connection
        // this service is given, enforced by SQLite rather than by parsing intent from a string.
        if (!upper.startsWith("SELECT") && !upper.startsWith("EXPLAIN") && !upper.startsWith("WITH")) {
            return new QueryResult(List.of(), List.of(), 0,
                    "Only SELECT, WITH and EXPLAIN queries are accepted here.");
        }

        long start = System.currentTimeMillis();
        List<String> columns = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();

        try (Statement stmt = connection.createStatement()) {
            stmt.setMaxRows(200);
            // Bounds a pathological aggregation over a table that spans the whole retention window.
            stmt.setQueryTimeout(5);
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

    /** Closes the read-only connection this service owns. */
    public void close() {
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // Nothing useful to do while shutting down.
        }
    }

    private static String escapeJson(@Nullable String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
