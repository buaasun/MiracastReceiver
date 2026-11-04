package com.weekd.miracastreceiver.miracast.probe;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Captures only input supplied by a successful codec-queue observer. No Android APIs. */
public final class QueuedInputCapture implements Closeable {
    interface Clock { long nanoTime(); }
    interface OutputFactory { OutputStream open(File file) throws IOException; }
    private static final int SUMMARY_RESERVE = 4096;
    private static final byte[] INDEX_HEADER =
            "seq\toffset\tlen\tpts_us\tqueued_mono_ns\n".getBytes(StandardCharsets.UTF_8);

    private final Object lock = new Object();
    private final File directory;
    private final String codecName;
    private final long durationNs, maxBytes, startMonoNs, startWallMs;
    private final Clock clock;
    private final OutputFactory outputs;
    private final ArrayBlockingQueue<Input> queue;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final Thread worker;
    private boolean accepting = true;
    private String endReason = "running", error = "";
    private long acceptedRecords, acceptedBytes, writtenRecords, writtenBytes, droppedRecords;
    private long reservedBytes;
    private long firstPtsUs = -1, lastPtsUs = -1, firstQueuedNs = -1, lastQueuedNs = -1;

    public QueuedInputCapture(File root, String label, String codecName, long durationMs,
            long maxBytes, int queueCapacity, byte[] csd0, byte[] csd1) throws IOException {
        this(root, label, codecName, durationMs, maxBytes, queueCapacity, csd0, csd1,
                System::nanoTime, FileOutputStream::new);
    }

    QueuedInputCapture(File root, String label, String codecName, long durationMs,
            long maxBytes, int queueCapacity, byte[] csd0, byte[] csd1, Clock clock,
            OutputFactory outputs) throws IOException {
        if (durationMs <= 0 || durationMs > 8000 || maxBytes < 65536 ||
                maxBytes > 16L * 1024 * 1024 || queueCapacity < 1 || queueCapacity > 16) {
            throw new IllegalArgumentException("Invalid bounded capture settings");
        }
        if ((long) csd0.length + csd1.length > 65536) {
            throw new IllegalArgumentException("Codec configuration exceeds 64 KiB");
        }
        this.reservedBytes = SUMMARY_RESERVE + INDEX_HEADER.length + csd0.length + csd1.length;
        if (reservedBytes >= maxBytes) throw new IllegalArgumentException("Capture budget too small for CSD");
        this.codecName = codecName.length() > 128 ? codecName.substring(0, 128) : codecName;
        this.durationNs = TimeUnit.MILLISECONDS.toNanos(durationMs);
        this.maxBytes = maxBytes;
        this.clock = clock;
        this.outputs = outputs;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.startMonoNs = clock.nanoTime();
        this.startWallMs = System.currentTimeMillis();
        this.directory = new File(root, RenderProbeConfig.safeLabel(label) + "-" + startWallMs +
                "-" + UUID.randomUUID().toString().substring(0, 8));
        if (!directory.mkdirs()) throw new IOException("Cannot create capture directory");
        byte[] sps = csd0.clone(), pps = csd1.clone();
        this.worker = new Thread(() -> writeCapture(sps, pps), "QueuedInputCapture");
        worker.setDaemon(true);
        worker.start();
    }

    /** Never performs file I/O or waits for the writer. Copies bytes before returning. */
    public boolean record(byte[] data, long ptsUs, long queuedMonoNs) {
        synchronized (lock) {
            expireIfDue();
            if (!accepting) return false;
            String row = (acceptedRecords + 1) + "\t" + acceptedBytes + "\t" + data.length +
                    "\t" + ptsUs + "\t" + queuedMonoNs + "\n";
            byte[] index = row.getBytes(StandardCharsets.UTF_8);
            if (reservedBytes + data.length + index.length > maxBytes) {
                droppedRecords++;
                finish("byte_limit");
                return false;
            }
            if (queue.remainingCapacity() == 0) {
                droppedRecords++;
                finish("queue_overflow");
                return false;
            }
            Input input = new Input(data.clone(), index);
            if (!queue.offer(input)) {
                droppedRecords++;
                finish("queue_overflow");
                return false;
            }
            if (acceptedRecords == 0) { firstPtsUs = ptsUs; firstQueuedNs = queuedMonoNs; }
            lastPtsUs = ptsUs;
            lastQueuedNs = queuedMonoNs;
            acceptedRecords++;
            acceptedBytes += data.length;
            reservedBytes += data.length + index.length;
            return true;
        }
    }

    public File directory() { return directory; }
    public boolean awaitFinished(long timeoutMs) throws InterruptedException {
        return finished.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override public void close() throws IOException {
        synchronized (lock) { if (accepting) finish("session_closed"); }
        try {
            if (!awaitFinished(2000)) throw new IOException("Capture writer close timed out; no finalized summary");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for capture flush", e);
        }
    }

    private void expireIfDue() {
        if (accepting && clock.nanoTime() - startMonoNs >= durationNs) {
            finish(acceptedRecords == 0 ? "duration_no_input" : "duration_elapsed");
        }
    }

    private void finish(String reason) { accepting = false; endReason = reason; }

    private void writeCapture(byte[] csd0, byte[] csd1) {
        try {
            writeFile("csd-0.bin", csd0);
            writeFile("csd-1.bin", csd1);
            try (OutputStream payload = outputs.open(new File(directory, "payload.h264"));
                    OutputStream index = outputs.open(new File(directory, "queued-inputs.tsv"))) {
                index.write(INDEX_HEADER);
                while (true) {
                    synchronized (lock) {
                        expireIfDue();
                        if (!accepting && queue.isEmpty()) break;
                    }
                    Input input = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (input == null) continue;
                    payload.write(input.bytes);
                    index.write(input.index);
                    synchronized (lock) { writtenRecords++; writtenBytes += input.bytes.length; }
                }
            }
        } catch (Exception e) {
            synchronized (lock) {
                finish("io_error");
                String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
                error = detail.substring(0, Math.min(detail.length(), 256));
            }
        } finally {
            queue.clear();
            try { writeSummary(); }
            catch (IOException e) { System.err.println("Render probe summary could not be finalized: " + e); }
            finished.countDown();
        }
    }

    private void writeFile(String name, byte[] bytes) throws IOException {
        try (OutputStream output = outputs.open(new File(directory, name))) { output.write(bytes); }
    }

    private void writeSummary() throws IOException {
        Properties summary = new Properties();
        synchronized (lock) {
            summary.setProperty("version", "1");
            summary.setProperty("codec_name", codecName);
            summary.setProperty("capture_id", directory.getName());
            summary.setProperty("complete", String.valueOf("duration_elapsed".equals(endReason) &&
                    error.isEmpty() && droppedRecords == 0 && writtenRecords == acceptedRecords));
            summary.setProperty("end_reason", endReason);
            summary.setProperty("error", error);
            summary.setProperty("accepted_records", String.valueOf(acceptedRecords));
            summary.setProperty("written_records", String.valueOf(writtenRecords));
            summary.setProperty("dropped_records", String.valueOf(droppedRecords));
            summary.setProperty("accepted_payload_bytes", String.valueOf(acceptedBytes));
            summary.setProperty("written_payload_bytes", String.valueOf(writtenBytes));
            summary.setProperty("max_total_bytes", String.valueOf(maxBytes));
            summary.setProperty("started_wall_ms", String.valueOf(startWallMs));
            summary.setProperty("started_mono_ns", String.valueOf(startMonoNs));
            summary.setProperty("finished_mono_ns", String.valueOf(clock.nanoTime()));
            summary.setProperty("duration_ms", String.valueOf(TimeUnit.NANOSECONDS.toMillis(durationNs)));
            summary.setProperty("first_pts_us", String.valueOf(firstPtsUs));
            summary.setProperty("last_pts_us", String.valueOf(lastPtsUs));
            summary.setProperty("first_queued_mono_ns", String.valueOf(firstQueuedNs));
            summary.setProperty("last_queued_mono_ns", String.valueOf(lastQueuedNs));
            summary.setProperty("payload_semantics", "successful_queue_input_only; no CSD prefix injected");
            summary.setProperty("csd_semantics", "csd-0.bin/csd-1.bin are codec configuration only, not queued inputs");
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        summary.store(encoded, "Finalized queued-input capture; not physical presentation evidence");
        if (encoded.size() > SUMMARY_RESERVE) throw new IOException("Summary exceeds reserved budget");
        File temporary = new File(directory, "summary.tmp");
        try (OutputStream output = outputs.open(temporary)) { output.write(encoded.toByteArray()); }
        if (!temporary.renameTo(new File(directory, "summary.properties"))) {
            throw new IOException("Cannot finalize capture summary");
        }
    }

    private static final class Input {
        final byte[] bytes, index;
        Input(byte[] bytes, byte[] index) { this.bytes = bytes; this.index = index; }
    }
}
