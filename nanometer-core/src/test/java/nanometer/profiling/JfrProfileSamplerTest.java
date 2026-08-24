package nanometer.profiling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class JfrProfileSamplerTest {

    @Test
    public void testRecordStackTraceAndFlamegraphJson() {
        JfrProfileSampler sampler = new JfrProfileSampler();
        assertEquals(0, sampler.getSampleCount());

        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        sampler.recordStackTrace(stack, 50_000_000L);
        assertEquals(1, sampler.getSampleCount());

        String json = sampler.generateFlamegraphJson();
        assertNotNull(json);
        assertTrue(json.contains("\"name\":\"root\""));
        assertTrue(json.contains("\"value\":50000000"));
        assertTrue(json.contains("JfrProfileSamplerTest"));

        // Empty/null stack
        sampler.recordStackTrace(null, 1000L);
        sampler.recordStackTrace(new StackTraceElement[0], 1000L);
        assertEquals(1, sampler.getSampleCount());

        sampler.clear();
        assertEquals(0, sampler.getSampleCount());
    }
}
