package nanometer.it.driver;

import nanometer.Nanometer;
import nanometer.it.subject.AgentTarget;
import nanometer.storage.MetricQueryService;

/**
 * Exercises the full embedded path from inside a JVM whose classpath is the shaded uber-JAR:
 * install, instrument, flush to SQLite, read back.
 *
 * <p>This is the path no test covered. The unit suite runs against the unshaded module classpath,
 * where org.sqlite is intact, so two defects that made the shipped JAR unusable both passed a green
 * build: the JDBC service entry still named a class that relocation had renamed, and relocating a
 * JNI library left the native binary exporting symbols under the old package.
 */
public final class StorageDriver {

    private StorageDriver() {
    }

    public static void main(String[] args) throws Exception {
        Nanometer.install("nanometer.it.subject");
        try {
            AgentTarget target = new AgentTarget();
            for (int i = 0; i < 20; i++) {
                target.parent();
            }

            MetricQueryService query = Nanometer.getQueryService();
            if (query == null) {
                throw new IllegalStateException("no query service after install");
            }

            // Deliberately hand-rolled rather than AsyncAssert.awaitUntil. This class runs in a
            // forked JVM whose classpath is the shaded uber-JAR and nothing else, which is the
            // whole point of the test: adding a test library to that classpath would weaken the
            // only check that the shipped artifact stands on its own.
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline && "0".equals(rowCount(query))) {
                Thread.sleep(100);
            }

            System.out.println("ROWS=" + rowCount(query));
        } finally {
            Nanometer.shutdown();
        }
    }

    private static String rowCount(MetricQueryService query) {
        MetricQueryService.QueryResult result =
                query.executeQuery("SELECT count(*) FROM execution_metrics");
        if (result.error() != null) {
            throw new IllegalStateException("query failed: " + result.error());
        }
        return result.rows().isEmpty() ? "0" : result.rows().get(0).get(0);
    }
}
