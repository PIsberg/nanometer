package nanometer.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SystemMetricsSamplerTest {

    @Test
    public void testCaptureSnapshotAndJsonSerialization() {
        SystemMetricsSampler.SystemSnapshot snapshot = SystemMetricsSampler.captureSnapshot();
        assertNotNull(snapshot);

        assertTrue(snapshot.processCpuPercent() >= 0.0);
        assertTrue(snapshot.systemCpuPercent() >= 0.0);
        assertTrue(snapshot.heapUsedMb() >= 0);
        assertTrue(snapshot.heapMaxMb() > 0);
        assertTrue(snapshot.nonHeapUsedMb() >= 0);
        assertTrue(snapshot.threadCount() > 0);
        assertTrue(snapshot.uptimeMs() > 0);
        assertTrue(snapshot.availableProcessors() > 0);

        String json = snapshot.toJson();
        assertNotNull(json);
        assertTrue(json.contains("processCpu"));
        assertTrue(json.contains("systemCpu"));
        assertTrue(json.contains("heapUsedMb"));
        assertTrue(json.contains("heapMaxMb"));
        assertTrue(json.contains("threadCount"));
        assertTrue(json.contains("uptimeMs"));
        assertTrue(json.contains("processors"));

        SystemMetricsSampler sampler = new SystemMetricsSampler();
        assertNotNull(sampler);
    }

    @Test
    public void testManualSnapshotRecordValues() {
        SystemMetricsSampler.SystemSnapshot snapshot = new SystemMetricsSampler.SystemSnapshot(
                15.5,
                45.2,
                128,
                1024,
                64,
                12,
                60000,
                8
        );

        assertEquals(15.5, snapshot.processCpuPercent());
        assertEquals(45.2, snapshot.systemCpuPercent());
        assertEquals(128, snapshot.heapUsedMb());
        assertEquals(1024, snapshot.heapMaxMb());
        assertEquals(64, snapshot.nonHeapUsedMb());
        assertEquals(12, snapshot.threadCount());
        assertEquals(60000, snapshot.uptimeMs());
        assertEquals(8, snapshot.availableProcessors());

        String json = snapshot.toJson();
        assertTrue(json.contains("\"processCpu\":15.50"));
        assertTrue(json.contains("\"heapUsedMb\":128"));
    }
}
