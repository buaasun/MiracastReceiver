package com.weekd.miracastreceiver.miracast.probe;

import static org.junit.Assert.*;
import java.util.Properties;
import org.junit.Test;

public class RenderProbeConfigTest {
    @Test public void defaultsAreAutoAndNoCapture() {
        RenderProbeConfig defaults = RenderProbeConfig.parse(new Properties());
        assertEquals("auto", defaults.codec);
        assertEquals(0, defaults.captureSeconds);
        assertNull(defaults.decoderName());
    }

    @Test public void explicitSoftwareAndUntrustedLimitsAreHandled() {
        Properties p = new Properties();
        p.setProperty("codec", "software");
        p.setProperty("capture_seconds", "100000");
        p.setProperty("capture_max_bytes", "999999999");
        p.setProperty("label", "../../bad\\name hello");
        RenderProbeConfig config = RenderProbeConfig.parse(p);
        assertEquals("OMX.google.h264.decoder", config.decoderName());
        assertEquals(8, config.captureSeconds);
        assertEquals(16 * 1024 * 1024, config.captureMaxBytes);
        assertFalse(config.label.contains("/"));
        assertFalse(config.label.contains("\\"));
        p.setProperty("codec", "unknown");
        assertThrows(IllegalArgumentException.class, () -> RenderProbeConfig.parse(p));
    }
}
