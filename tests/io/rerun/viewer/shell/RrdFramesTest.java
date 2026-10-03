package io.rerun.viewer.shell;

import java.io.*;
import java.util.Arrays;

/** Structural RRF2 tests; these do not claim Arrow payload decoding or UI playback. */
public final class RrdFramesTest {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static void rejects(Class<? extends Throwable> type, Action action) throws Exception {
        try { action.run(); }
        catch (Throwable error) {
            check(type.isInstance(error), "Expected " + type + ", got " + error);
            return;
        }
        throw new AssertionError("Expected " + type);
    }
    private static byte[] join(byte[]... chunks) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) out.write(chunk);
        return out.toByteArray();
    }
    private static byte[] frame(int seed, int length) {
        byte[] bytes = new byte[length + 8];
        bytes[0] = (byte) length;
        bytes[4] = (byte) length;
        for (int i = 8; i < bytes.length; i++) bytes[i] = (byte) (seed + i);
        return bytes;
    }
    private static byte[] drain(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[7];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (n == 0) throw new AssertionError("Unexpected zero-length read");
                out.write(buffer, 0, n);
            }
            check(in.read(new byte[0]) == 0, "Zero-length read at EOF");
            return out.toByteArray();
        }
    }
    private static InputStream shortReads(byte[] data) {
        return new ByteArrayInputStream(data) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(2, len));
            }
            @Override public synchronized long skip(long count) { return 0; }
        };
    }
    public static void main(String[] args) throws Exception {
        byte[] header = new byte[]{'R','R','F','2',0,0x13,0,0,0,0,0,0};
        byte[] metadata = frame(10, 5);
        byte[][] data = {frame(30, 11), frame(60, 13), frame(90, 17)};
        byte[] prefix = join(header, metadata);
        byte[] recording = join(prefix, data[0], data[1], data[2], new byte[8]);
        File file = File.createTempFile("rerun-test-", ".rrd", new File("."));
        try {
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(recording); }
            final RrdFrames.Index index = RrdFrames.scan(file, 1, 1);
            RrdFrames.Index streamed = RrdFrames.scan(shortReads(recording), recording.length, 1, 1);
            check(index.frames.size() == 4, "Metadata + 3 data frames");
            check(index.preambleEnd == prefix.length, "Preamble length");
            check(index.segments.size() == 3, "Byte budget yields 3 segments");
            check(streamed.frames.size() == index.frames.size(), "Scanner parity");
            for (int i = 0; i < 3; i++) {
                byte[] expected = join(prefix, data[i], new byte[8]);
                check(Arrays.equals(expected, drain(RrdFrames.open(file, index, i))), "Segment " + i);
                check(Arrays.equals(expected, drain(RrdFrames.open(shortReads(recording), streamed, i))), "Short segment " + i);
                check(index.frames.get(i + 1).fileOffset == streamed.frames.get(i + 1).fileOffset, "Offset parity");
                check(Arrays.equals(expected, drain(new RrdFrames.FrameWindowStream(shortReads(recording), index, i, i + 1))), "Window " + i);
            }
            check(Arrays.equals(recording, drain(new RrdFrames.FrameWindowStream(shortReads(recording), index, 0, 3))), "Full window");
            java.util.List<Integer> sparseFrames = java.util.Arrays.asList(1, 3);
            RrdFrames.Index sparse = index.withSelectedParts(java.util.Collections.singletonList(sparseFrames));
            byte[] sparseExpected = join(prefix, data[0], data[2], new byte[8]);
            check(Arrays.equals(sparseExpected, drain(new RrdFrames.SparseStream(shortReads(recording), sparse, sparseFrames))), "Sparse stream");
            check(sparse.segments.size() == 1 && sparse.selectedParts.get(0).equals(sparseFrames), "Sparse index");

            check(Arrays.equals(join(prefix, new byte[8]), drain(new RrdFrames.FrameWindowStream(shortReads(recording), index, 1, 1))), "Empty window");
            rejects(IllegalArgumentException.class, () -> new RrdFrames.FrameWindowStream(new ByteArrayInputStream(recording), index, -1, 1));
            rejects(IllegalArgumentException.class, () -> new RrdFrames.FrameWindowStream(new ByteArrayInputStream(recording), index, 2, 1));
            rejects(IllegalArgumentException.class, () -> new RrdFrames.FrameWindowStream(new ByteArrayInputStream(recording), index, 0, 4));
            rejects(IOException.class, () -> drain(new RrdFrames.FrameWindowStream(shortReads(Arrays.copyOf(recording, prefix.length - 1)), index, 0, 3)));
            rejects(IOException.class, () -> drain(new RrdFrames.FrameWindowStream(shortReads(Arrays.copyOf(recording, recording.length - 10)), index, 0, 3)));
            rejects(IOException.class, () -> drain(new RrdFrames.FrameWindowStream(shortReads(Arrays.copyOf(recording, prefix.length + 1)), index, 2, 3)));
            rejects(IndexOutOfBoundsException.class, () -> new RrdFrames.FrameWindowStream(shortReads(recording), index, 0, 1).read(new byte[2], 0, 3));
            rejects(NullPointerException.class, () -> new RrdFrames.FrameWindowStream(shortReads(recording), index, 0, 1).read(null, 0, 0));
            rejects(IOException.class, () -> RrdFrames.scan(shortReads(Arrays.copyOf(recording, recording.length - 1)), recording.length - 1));
            rejects(IOException.class, () -> RrdFrames.scan(shortReads(recording), -1));
            byte[] invalid = recording.clone(); invalid[5] = 0x20;
            rejects(IOException.class, () -> RrdFrames.scan(shortReads(invalid), invalid.length));
            final boolean[] closed = {false};
            InputStream source = new ByteArrayInputStream(recording) {
                @Override public void close() { closed[0] = true; }
            };
            new RrdFrames.FrameWindowStream(source, index, 0, 1).close();
            check(closed[0], "Source closed");
            System.out.println("PASS RrdFramesTest: " + checks + " checks");
        } finally { file.delete(); }
    }
}
