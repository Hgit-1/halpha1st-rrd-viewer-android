package io.rerun.viewer.shell;

import java.io.*;
import java.security.MessageDigest;
import java.util.Arrays;

/** Optional real-file structural test. No Arrow decoding or rendering claims. */
public final class RealRecordingTest {
    private static byte[] digest(InputStream stream, long expected) throws Exception {
        try (InputStream input = stream) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) throw new AssertionError("Zero read");
                total += count;
                digest.update(buffer, 0, count);
            }
            if (total != expected) throw new AssertionError(total + " != " + expected);
            return digest.digest();
        }
    }
    public static void main(String[] args) throws Exception {
        File file = new File(args[0]);
        long budget = 4L * 1024 * 1024; // Normal-device application budget.
        RrdFrames.Index index = RrdFrames.scan(file, budget);
        RrdFrames.Index sequential;
        try (InputStream input = new FileInputStream(file)) {
            sequential = RrdFrames.scan(input, file.length(), budget);
        }
        if (index.frames.size() != sequential.frames.size()
                || index.segments.size() != sequential.segments.size())
            throw new AssertionError("Scanner disagreement");
        int frame = 1; // Skip metadata, which is already in every preamble.
        int checked = 0;
        for (int part = 0; part < index.segments.size(); part++) {
            RrdFrames.Segment segment = index.segments.get(part);
            RrdFrames.Segment other = sequential.segments.get(part);
            if (segment.start != other.start || segment.end != other.end)
                throw new AssertionError("Segment boundary disagreement");
            int start = frame - 1;
            while (frame < index.frames.size() && index.frames.get(frame).fileOffset < segment.end) {
                RrdFrames.FrameInfo a = index.frames.get(frame);
                RrdFrames.FrameInfo b = sequential.frames.get(frame);
                if (a.fileOffset != b.fileOffset || a.encodedSize != b.encodedSize)
                    throw new AssertionError("Frame disagreement");
                frame++;
            }
            long expected = index.preambleEnd + segment.sampledLength + 8;
            byte[] original = digest(RrdFrames.open(file, index, part), expected);
            byte[] window = digest(new RrdFrames.FrameWindowStream(
                    new FileInputStream(file), index, start, frame - 1), expected);
            if (!Arrays.equals(original, window)) throw new AssertionError("Window mismatch " + part);
            checked++;
        }
        if (frame != index.frames.size()) throw new AssertionError("Missing frames");
        System.out.println("PASS RealRecordingTest: bytes=" + file.length()
                + " containerFrames=" + index.frames.size() + " dataFrames="
                + (index.frames.size() - 1) + " budget=" + budget + " segments=" + checked);
        System.out.println("Container frames are not playback ticks or image frames.");
    }
}
