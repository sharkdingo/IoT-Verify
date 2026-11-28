package cn.edu.nju.Iot_Verify.component.nusmv.executor;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NusmvOutputCollectorTest {

    @Test
    void outputIsTruncatedOnUtf8BoundaryWithinConfiguredByteLimit() throws Exception {
        NusmvExecutor.BoundedOutputCollector collector = new NusmvExecutor.BoundedOutputCollector(64);
        byte[] input = ("temperature=" + "\u6e29".repeat(40) + "\n").getBytes(StandardCharsets.UTF_8);

        NusmvExecutor.drainOutput(new ByteArrayInputStream(input), collector);
        String output = collector.text();

        assertTrue(output.endsWith("... (NuSMV output truncated)\n"));
        assertTrue(output.getBytes(StandardCharsets.UTF_8).length <= 64);
        assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(output));
        assertTrue(collector.isTruncated());
    }

    @Test
    void completeOutputIsPreservedWhenWithinLimit() throws Exception {
        NusmvExecutor.BoundedOutputCollector collector = new NusmvExecutor.BoundedOutputCollector(1024);
        byte[] input = "-- specification AG safe is true\n".getBytes(StandardCharsets.UTF_8);

        NusmvExecutor.drainOutput(new ByteArrayInputStream(input), collector);

        assertEquals("-- specification AG safe is true\n", collector.text());
        assertFalse(collector.isTruncated());
    }

    @Test
    void singleLineLargerThanTheLimitIsDrainedInBoundedChunks() throws Exception {
        byte[] output = ("x".repeat(1024 * 1024) + "\n").getBytes(StandardCharsets.UTF_8);
        NusmvExecutor.BoundedOutputCollector collector = new NusmvExecutor.BoundedOutputCollector(1024);

        NusmvExecutor.drainOutput(new ByteArrayInputStream(output), collector);

        assertTrue(collector.isTruncated());
        assertTrue(collector.text().getBytes(StandardCharsets.UTF_8).length <= 1024);
    }
}
