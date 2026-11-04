package com.weekd.miracastreceiver.miracast.probe;

import java.io.*;
import java.util.Properties;

/** Test-build settings. The Android caller must gate file access by BuildConfig. */
public final class RenderProbeConfig {
    public final String codec;
    public final int captureSeconds;
    public final long captureMaxBytes;
    public final String label;

    private RenderProbeConfig(String codec, int seconds, long maxBytes, String label) {
        this.codec = codec;
        this.captureSeconds = seconds;
        this.captureMaxBytes = maxBytes;
        this.label = label;
    }

    public static RenderProbeConfig parse(Properties properties) {
        String codec = properties.getProperty("codec", "auto").trim();
        if (!codec.equals("auto") && !codec.equals("software")) {
            throw new IllegalArgumentException("codec must be auto or software");
        }
        long seconds = Long.parseLong(properties.getProperty("capture_seconds", "0").trim());
        long maxBytes = Long.parseLong(properties.getProperty("capture_max_bytes", "16777216").trim());
        return new RenderProbeConfig(codec, (int) Math.max(0, Math.min(8, seconds)),
                Math.max(65536, Math.min(16L * 1024 * 1024, maxBytes)),
                safeLabel(properties.getProperty("label", "probe")));
    }

    static String safeLabel(String label) {
        String safe = label.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isEmpty()) safe = "probe";
        return safe.substring(0, Math.min(48, safe.length()));
    }

    public String decoderName() {
        return "software".equals(codec) ? "OMX.google.h264.decoder" : null;
    }

    public static RenderProbeConfig load(File file) throws IOException {
        Properties properties = new Properties();
        if (file.isFile()) try (InputStream input = new FileInputStream(file)) {
            properties.load(input);
        }
        return parse(properties);
    }
}
