package com.weekd.miracastreceiver.miracast.probe;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class RenderProbeCaptureTest {
    private static final long BUDGET = 65536;

    @Test public void recordsOnlySuppliedQueueBytesWithExactOffsetsAndSeparateCsd() throws Exception {
        File root = Files.createTempDirectory("queued-probe").toFile();
        byte[] csd0 = {0, 0, 0, 1, 0x67};
        byte[] csd1 = {0, 0, 0, 1, 0x68};
        QueuedInputCapture capture = new QueuedInputCapture(root, "test", "codec-a", 8000,
                BUDGET, 16, csd0, csd1);
        byte[] first = {0, 0, 0, 1, 0x65, 11};
        assertTrue(capture.record(first, 123, 1001));
        first[5] = 99; // Caller reuse cannot alter the asynchronously saved input.
        assertTrue(capture.record(new byte[]{0, 0, 1, 0x41}, 456, 1002));
        capture.close();
        File folder = capture.directory();
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x65, 11, 0, 0, 1, 0x41},
                Files.readAllBytes(new File(folder, "payload.h264").toPath()));
        assertArrayEquals(csd0, Files.readAllBytes(new File(folder, "csd-0.bin").toPath()));
        assertArrayEquals(csd1, Files.readAllBytes(new File(folder, "csd-1.bin").toPath()));
        List<String> rows = Files.readAllLines(new File(folder, "queued-inputs.tsv").toPath());
        assertEquals("1\t0\t6\t123\t1001", rows.get(1));
        assertEquals("2\t6\t4\t456\t1002", rows.get(2));
        Properties summary = summary(folder);
        assertEquals("codec-a", summary.getProperty("codec_name"));
        assertEquals("2", summary.getProperty("written_records"));
        assertEquals("false", summary.getProperty("complete"));
        assertEquals("session_closed", summary.getProperty("end_reason"));
    }

    @Test public void durationFinishesWithoutSessionCloseOrFurtherInput() throws Exception {
        AtomicLong clock = new AtomicLong(1);
        QueuedInputCapture capture = new QueuedInputCapture(Files.createTempDirectory("deadline").toFile(),
                "duration", "codec", 8000, BUDGET, 16, new byte[0], new byte[0], clock::get,
                FileOutputStream::new);
        assertTrue(capture.record(new byte[]{1, 2}, 7, 8));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(8));
        assertTrue(capture.awaitFinished(2000));
        assertEquals("true", summary(capture.directory()).getProperty("complete"));
        assertEquals("duration_elapsed", summary(capture.directory()).getProperty("end_reason"));
        capture.close();
    }

    @Test public void byteLimitIncludesMetadataAndNeverWritesPartialInput() throws Exception {
        QueuedInputCapture capture = new QueuedInputCapture(Files.createTempDirectory("budget").toFile(),
                "limit", "codec", 8000, BUDGET, 16, new byte[]{7}, new byte[]{8});
        assertTrue(capture.record(new byte[40000], 1, 1));
        assertFalse(capture.record(new byte[40000], 2, 2));
        assertTrue(capture.awaitFinished(2000));
        Properties summary = summary(capture.directory());
        assertEquals("byte_limit", summary.getProperty("end_reason"));
        assertEquals("false", summary.getProperty("complete"));
        assertEquals(40000, new File(capture.directory(), "payload.h264").length());
        long total = 0;
        for (File file : Objects.requireNonNull(capture.directory().listFiles())) total += file.length();
        assertTrue("All capture files fit the configured budget", total <= BUDGET);
    }

    @Test public void delayedWindowSkipsEarlyInputsAndFinishesAtDelayPlusDuration() throws Exception {
        AtomicLong clock = new AtomicLong(100);
        QueuedInputCapture capture = new QueuedInputCapture(Files.createTempDirectory("delayed").toFile(),
                "delayed", "codec", 8000, 30000, BUDGET, 16, new byte[0], new byte[0],
                clock::get, FileOutputStream::new);
        try {
            assertFalse(capture.record(new byte[]{1}, 1, clock.get()));
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(29999));
            assertFalse(capture.record(new byte[]{2}, 2, clock.get()));
            assertEquals(0, new File(capture.directory(), "payload.h264").length());
            assertFalse(capture.awaitFinished(80));
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
            assertTrue(capture.record(new byte[]{3}, 3, clock.get()));
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(7999));
            assertTrue(capture.record(new byte[]{4}, 4, clock.get()));
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
            assertTrue(capture.awaitFinished(2000)); // No more input and no session close.
            assertFalse(capture.record(new byte[]{5}, 5, clock.get()));
            assertArrayEquals(new byte[]{3, 4}, Files.readAllBytes(
                    new File(capture.directory(), "payload.h264").toPath()));
            Properties summary = summary(capture.directory());
            assertEquals("true", summary.getProperty("complete"));
            assertEquals("30000", summary.getProperty("delay_ms"));
            assertEquals("2", summary.getProperty("delayed_records"));
            assertEquals("2", summary.getProperty("written_records"));
        } finally { capture.close(); }
    }

    @Test public void slowDiskDoesNotBlockProducerAndOverflowIsExplicit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), unblock = new CountDownLatch(1);
        QueuedInputCapture.OutputFactory output = file -> {
            FileOutputStream delegate = new FileOutputStream(file);
            if (!file.getName().equals("payload.h264")) return delegate;
            return new FilterOutputStream(delegate) {
                @Override public void write(byte[] b) throws IOException {
                    entered.countDown();
                    try { if (!unblock.await(3, TimeUnit.SECONDS)) throw new IOException("test timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                    out.write(b);
                }
            };
        };
        QueuedInputCapture capture = new QueuedInputCapture(Files.createTempDirectory("overflow").toFile(),
                "overflow", "codec", 8000, BUDGET, 1, new byte[0], new byte[0], System::nanoTime, output);
        try {
            assertTrue(capture.record(new byte[]{1}, 1, 1));
            assertTrue(entered.await(1000, TimeUnit.MILLISECONDS));
            assertTrue(capture.record(new byte[]{2}, 2, 2));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try { assertFalse(executor.submit(() -> capture.record(new byte[]{3}, 3, 3))
                    .get(500, TimeUnit.MILLISECONDS)); }
            finally { executor.shutdownNow(); }
        } finally { unblock.countDown(); capture.close(); }
        assertEquals("queue_overflow", summary(capture.directory()).getProperty("end_reason"));
        assertEquals("false", summary(capture.directory()).getProperty("complete"));
        assertEquals("1", summary(capture.directory()).getProperty("dropped_records"));
    }

    @Test public void failedWriteMarksCaptureIncompleteAndCodecInstancesNeverShareFiles() throws Exception {
        File root = Files.createTempDirectory("failure").toFile();
        QueuedInputCapture capture = new QueuedInputCapture(root, "same", "codec-a", 8000,
                BUDGET, 16, new byte[0], new byte[0], System::nanoTime,
                file -> {
                    if (file.getName().equals("payload.h264")) return new OutputStream() {
                        @Override public void write(int b) throws IOException { throw new IOException("disk unavailable"); }
                    };
                    return new FileOutputStream(file);
                });
        capture.record(new byte[]{1}, 1, 1);
        assertTrue(capture.awaitFinished(2000));
        Properties summary = summary(capture.directory());
        assertEquals("io_error", summary.getProperty("end_reason"));
        assertEquals("false", summary.getProperty("complete"));
        assertTrue(summary.getProperty("error").contains("IOException"));
        QueuedInputCapture second = new QueuedInputCapture(root, "same", "codec-b", 8000,
                BUDGET, 16, new byte[0], new byte[0]);
        second.close();
        assertNotEquals(capture.directory(), second.directory());
        assertEquals("codec-b", summary(second.directory()).getProperty("codec_name"));
    }

    private static Properties summary(File directory) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = new FileInputStream(new File(directory, "summary.properties"))) {
            properties.load(stream);
        }
        return properties;
    }
}
