package io.rerun.viewer.shell;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Disk-backed live RRF2 tail. Receiver visibility advances only at complete frame boundaries. */
final class RrdLiveSession {
    interface Listener { void onState(String text); }
    private static final int HEADER_BYTES = 12;
    private static final int FRAME_HEADER_BYTES = 8;
    private static final long TARGET_BYTES = 16L * 1024 * 1024;
    private static final int MAX_FRAME_BYTES = 256 * 1024 * 1024;
    private static final int MAX_PARTS = 12;
    /** Keep one complete data frame out of four; never decode or split a frame. */
    private static final int SAMPLE_EVERY = 1;

    private static final class Part {
        final File file;
        BufferedOutputStream output;
        long written;
        long visible;
        boolean closed;
        boolean terminated;
        Part(File file) { this.file = file; }
    }

    private final String address;
    private final File directory;
    private final String stem;
    private final Listener listener;
    private final Object lock = new Object();
    private final List<Part> parts = new ArrayList<Part>();
    private volatile java.net.HttpURLConnection upstream;
    private volatile boolean stopped;
    private volatile boolean done;
    private volatile Throwable failure;
    private Thread receiver;
    private byte[] fileHeader;
    private byte[] metadataFrame;
    private Part current;
    private Part activeReader;
    private boolean readerTooSlow;
    private int partNumber;
    private int dataFrameNumber;

    RrdLiveSession(String address, File file, Listener listener) {
        this.address = address;
        this.directory = file.getParentFile();
        this.stem = file.getName().endsWith(".rrd")
                ? file.getName().substring(0, file.getName().length() - 4) : file.getName();
        this.listener = listener;
    }

    void start() {
        receiver = new Thread(new Runnable() {
            @Override public void run() { receive(); }
        }, "rerun-live-dvr-receiver");
        receiver.setDaemon(true);
        receiver.start();
    }

    void stop() {
        stopped = true;
        java.net.HttpURLConnection connection = upstream;
        if (connection != null) connection.disconnect();
        synchronized (lock) { lock.notifyAll(); }
        if (receiver != null) receiver.interrupt();
    }

    /** Writes one continuous RRF2 stream, following disk part rotations safely. */
    void streamTo(OutputStream output) throws IOException {
        Part part;
        synchronized (lock) {
            while (!stopped && parts.isEmpty() && !done) waitLocked();
            if (parts.isEmpty()) throw new IOException("Live DVR has no recording yet");
            part = parts.get(parts.size() - 1);
            activeReader = part;
            readerTooSlow = false;
        }
        try {
            long position = 0;
            byte[] buffer = new byte[65536];
            for (;;) {
                long limit;
                synchronized (lock) {
                    if (readerTooSlow) throw new IOException("Viewer fell behind DVR retention window");
                    while (!stopped && !done && position >= part.visible && !part.closed && !readerTooSlow)
                        waitLocked();
                    if (readerTooSlow) throw new IOException("Viewer fell behind DVR retention window");
                    limit = part.visible;
                    // A terminator belongs only at the end of a continuous stream.
                    // Rotation parts carry one on disk so they remain independently
                    // readable, but it must not terminate the live HTTP receiver.
                    // A rotated part is closed before the next part is created.
                    // Never expose its on-disk terminator during that gap.
                    if (part.closed && (!done || parts.indexOf(part) + 1 < parts.size()))
                        limit -= 8;
                }
                if (position < limit) {
                    try (FileInputStream input = new FileInputStream(part.file)) {
                        long skipped = input.skip(position);
                        while (skipped < position) {
                            long more = input.skip(position - skipped);
                            if (more <= 0) throw new IOException("Unable to seek DVR part");
                            skipped += more;
                        }
                        while (position < limit) {
                            int count = input.read(buffer, 0, (int) Math.min(buffer.length, limit - position));
                            if (count < 0) break;
                            output.write(buffer, 0, count);
                            output.flush();
                            position += count;
                        }
                    }
                }
                synchronized (lock) {
                    if (readerTooSlow) throw new IOException("Viewer fell behind DVR retention window");
                    long readable = part.visible;
                    if (part.closed && (!done || parts.indexOf(part) + 1 < parts.size())) readable -= 8;
                    if (position < readable) continue;
                    int next = parts.indexOf(part) + 1;
                    if (next < parts.size()) {
                        part = parts.get(next);
                        activeReader = part;
                        // Later disk parts repeat header+metadata. Do not inject a
                        // second RRF2 header into the continuous viewer stream.
                        position = HEADER_BYTES + metadataFrame.length;
                        continue;
                    }
                    if (done || stopped) {
                        // The final part's eight-byte marker is intentionally sent.
                        break;
                    }
                    waitLocked();
                }
            }
            if (failure != null) throw new IOException("Live source stopped", failure);
        } finally {
            synchronized (lock) {
                if (activeReader == part) activeReader = null;
                if (readerTooSlow) {
                    readerTooSlow = false;
                    while (parts.size() > MAX_PARTS) {
                        Part old = parts.remove(0);
                        old.file.delete();
                    }
                }
                lock.notifyAll();
            }
        }
    }

    private void receive() {
        java.net.HttpURLConnection connection = null;
        try {
            if (directory != null && !directory.exists() && !directory.mkdirs())
                throw new IOException("Unable to create live DVR directory");
            connection = (java.net.HttpURLConnection) new java.net.URL(address).openConnection();
            upstream = connection;
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept", "application/octet-stream");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Upstream HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                fileHeader = readExact(input, HEADER_BYTES);
                if (fileHeader[0] != 'R' || fileHeader[1] != 'R'
                        || fileHeader[2] != 'F' || fileHeader[3] != '2'
                        || fileHeader[4] != 0 || fileHeader[5] != 0x13
                        || fileHeader[6] != 0 || fileHeader[7] != 0)
                    throw new IOException("Unsupported live RRF2 header/version");
                metadataFrame = readFrame(input, 16L * 1024 * 1024);
                openPart();
                append(fileHeader);
                append(metadataFrame);
                publish();
                for (;;) {
                    byte[] frameHeader = readExact(input, FRAME_HEADER_BYTES);
                    long encoded = uint32(frameHeader, 0);
                    long uncompressed = uint32(frameHeader, 4);
                    if (encoded == 0 && uncompressed == 0) {
                        append(frameHeader);
                        synchronized (lock) { current.terminated = true; }
                        closeCurrent();
                        break;
                    }
                    if (encoded == 0 || uncompressed == 0 || encoded > MAX_FRAME_BYTES
                            || uncompressed > MAX_FRAME_BYTES)
                        throw new IOException("Invalid live RRF2 frame");
                    boolean keepFrame = dataFrameNumber % SAMPLE_EVERY == 0;
                    dataFrameNumber++;
                    if (!keepFrame) {
                        copyPayload(input, encoded, false);
                        continue;
                    }
                    synchronized (lock) {
                        if (current.written > HEADER_BYTES + metadataFrame.length
                                && current.written + FRAME_HEADER_BYTES + encoded > TARGET_BYTES) {
                            closeCurrent();
                            openPart();
                            append(fileHeader);
                            append(metadataFrame);
                            publish();
                        }
                        append(frameHeader);
                    }
                    copyPayload(input, encoded, true);
                    // A retained frame becomes visible only after its complete payload is on disk.
                    publish();
                }
            }
        } catch (Throwable problem) {
            if (!stopped) {
                failure = problem;
                notifyState("直播接收失败：" + problem.getClass().getSimpleName());
            }
        } finally {
            if (connection != null) connection.disconnect();
            upstream = null;
            synchronized (lock) {
                done = true;
                if (current != null && !current.closed) closeCurrentLocked();
                lock.notifyAll();
            }
        }
    }

    private void openPart() throws IOException {
        synchronized (lock) {
            partNumber++;
            Part part = new Part(new File(directory, stem + "-" + String.format("%04d", partNumber) + ".rrd"));
            if (part.file.exists() && !part.file.delete()) throw new IOException("Cannot replace DVR part");
            part.output = new BufferedOutputStream(new FileOutputStream(part.file), 65536);
            parts.add(part);
            current = part;
            while (parts.size() > MAX_PARTS) {
                Part old = parts.get(0);
                if (old == activeReader) {
                    // Do not silently remove a middle frame: a slow continuous
                    // reader must disconnect and reconnect from a fresh part.
                    readerTooSlow = true;
                    lock.notifyAll();
                    break;
                }
                parts.remove(0);
                try { if (old.output != null) old.output.close(); } catch (IOException ignored) { }
                old.file.delete();
            }
            notifyStateLocked("直播已连接，DVR 正在磁盘滚动录制…");
        }
    }

    private void append(byte[] bytes) throws IOException {
        append(bytes, 0, bytes.length);
    }

    private void append(byte[] bytes, int offset, int length) throws IOException {
        synchronized (lock) {
            current.output.write(bytes, offset, length);
            current.written += length;
        }
    }

    private void publish() throws IOException {
        synchronized (lock) {
            current.output.flush();
            current.visible = current.written;
            lock.notifyAll();
        }
    }

    private void closeCurrent() throws IOException {
        synchronized (lock) { closeCurrentLocked(); }
    }

    private void closeCurrentLocked() {
        if (current == null || current.closed) return;
        try {
            if (!current.terminated) {
                current.output.write(new byte[8]);
                current.written += 8;
                current.terminated = true;
            }
            current.output.flush();
            current.visible = current.written;
            current.output.close();
        } catch (IOException ignored) { }
        current.closed = true;
        lock.notifyAll();
    }

    private void copyPayload(InputStream input, long length, boolean retain) throws IOException {
        byte[] buffer = new byte[65536];
        long remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new IOException("Truncated live RRF2 frame");
            if (retain) append(buffer, 0, count);
            remaining -= count;
        }
    }

    private void waitLocked() throws IOException {
        try { lock.wait(1000L); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Live DVR interrupted");
        }
    }

    private void notifyState(String text) { if (listener != null) listener.onState(text); }
    private void notifyStateLocked(String text) { notifyState(text); }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(result, offset, length - offset);
            if (count < 0) throw new IOException("Unexpected end of live stream");
            offset += count;
        }
        return result;
    }
    private static byte[] readFrame(InputStream input, long max) throws IOException {
        byte[] header = readExact(input, FRAME_HEADER_BYTES);
        long encoded = uint32(header, 0);
        long uncompressed = uint32(header, 4);
        if (encoded == 0 || uncompressed == 0 || encoded > max || uncompressed > max)
            throw new IOException("Invalid live metadata frame");
        byte[] frame = new byte[(int) encoded + FRAME_HEADER_BYTES];
        System.arraycopy(header, 0, frame, 0, FRAME_HEADER_BYTES);
        int offset = FRAME_HEADER_BYTES;
        while (offset < frame.length) {
            int count = input.read(frame, offset, frame.length - offset);
            if (count < 0) throw new IOException("Truncated live metadata frame");
            offset += count;
        }
        return frame;
    }
    private static long uint32(byte[] b, int offset) {
        return (long) (b[offset] & 255) | ((long) (b[offset + 1] & 255) << 8)
                | ((long) (b[offset + 2] & 255) << 16) | ((long) (b[offset + 3] & 255) << 24);
    }
}
