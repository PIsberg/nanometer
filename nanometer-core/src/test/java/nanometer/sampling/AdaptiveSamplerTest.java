package nanometer.sampling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AdaptiveSamplerTest {

    @Test
    public void testSamplingDecisions() {
        AdaptiveSampler sampler = new AdaptiveSampler(1.0, "com.example");

        assertTrue(sampler.shouldSample(10_000_000L, "NONE"));
        assertTrue(sampler.isClassIncluded("com.example.OrderService"));
        assertFalse(sampler.isClassIncluded("org.other.Service"));

        // 0% rate with tail sampling off
        sampler.setSampleRate(0.0);
        sampler.setTailSamplingEnabled(false);
        assertFalse(sampler.shouldSample(10_000_000L, "NONE"));
        assertFalse(sampler.shouldSample(10_000_000L, "RuntimeException"));

        // Enable tail sampling: error must be sampled
        sampler.setTailSamplingEnabled(true);
        assertTrue(sampler.shouldSample(10_000_000L, "RuntimeException"));

        // Slow trace (> 50ms) must be sampled
        assertTrue(sampler.shouldSample(60_000_000L, "NONE"));

        sampler.setSlowTraceThresholdMs(100.0);
        assertEquals(100.0, sampler.getSlowTraceThresholdMs());

        sampler.addPackage("com.test");
        assertTrue(sampler.isClassIncluded("com.test.App"));
        sampler.removePackage("com.test");
        assertFalse(sampler.isClassIncluded("com.test.App"));

        String json = sampler.toJson();
        assertNotNull(json);
        assertTrue(json.contains("sampleRate"));
        assertTrue(json.contains("tailSampling"));
    }
}
