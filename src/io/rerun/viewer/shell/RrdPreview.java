package io.rerun.viewer.shell;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Bounded marker probe, not an Arrow decoder. Never changes recording bytes. */
public final class RrdPreview {
    private RrdPreview() { }
    static final int MAX_BLOCK = 8 * 1024 * 1024;
    static final long MAX_SCAN = 64L * 1024 * 1024;

    public static int findSegment(InputStream in, RrdFrames.Index index, boolean boxes)
            throws IOException {
        byte[] header = new byte[12];
        readFully(in, header);
        int compression = header[8] & 255;
        if (header[9] != 1 || (compression != 0 && compression != 1)) return -1;
        byte[] marker = (boxes ? "Boxes2DIndicator" : "EncodedImageIndicator")
                .getBytes(StandardCharsets.UTF_8);
        long position = 12;
        long scanned = 0;
        for (RrdFrames.FrameInfo frame : index.frames) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
            long n = frame.encodedSize - 8;
            if (scanned + n + frame.uncompressedSize > MAX_SCAN) return -1;
            scanned += n + frame.uncompressedSize;
            skip(in, frame.fileOffset + 8 - position);
            if (n > MAX_BLOCK || frame.uncompressedSize > MAX_BLOCK) {
                skip(in, n);
            } else {
                byte[] encoded = new byte[(int) n];
                readFully(in, encoded);
                byte[] raw = compression == 1 ? decodeLz4(encoded, (int) frame.uncompressedSize) : encoded;
                if (contains(raw, marker)) {
                    for (int i = 0; i < index.segments.size(); i++) {
                        RrdFrames.Segment part = index.segments.get(i);
                        if (frame.fileOffset >= part.start && frame.fileOffset < part.end) return i;
                    }
                }
            }
            position = frame.fileOffset + frame.encodedSize;
        }
        return -1;
    }

    static boolean contains(byte[] data, byte[] marker) {
        outer: for (int i = 0; i <= data.length - marker.length; i++) {
            for (int j = 0; j < marker.length; j++) if (data[i+j] != marker[j]) continue outer;
            return true;
        }
        return false;
    }

    /** Raw LZ4 block used by legacy RRF2, with bounds checked before allocation/copy. */
    static byte[] decodeLz4(byte[] src, int length) throws IOException {
        if (length < 0 || length > MAX_BLOCK || src.length > MAX_BLOCK) throw new IOException("Block limit");
        byte[] dst = new byte[length];
        int s = 0, d = 0;
        while (s < src.length) {
            int token = src[s++] & 255;
            int literals = token >>> 4;
            if (literals == 15) {
                int v;
                do {
                    if (s >= src.length) throw new IOException("Truncated literal length");
                    v = src[s++] & 255; literals += v;
                    if (literals > length) throw new IOException("Literal overflow");
                } while (v == 255);
            }
            if (literals > src.length - s || literals > length - d) throw new IOException("Literal bounds");
            System.arraycopy(src, s, dst, d, literals); s += literals; d += literals;
            if (s == src.length) break;
            if (src.length - s < 2) throw new IOException("Truncated offset");
            int offset = (src[s] & 255) | ((src[s+1] & 255) << 8); s += 2;
            if (offset == 0 || offset > d) throw new IOException("Invalid offset");
            int match = (token & 15) + 4;
            if ((token & 15) == 15) {
                int v;
                do {
                    if (s >= src.length) throw new IOException("Truncated match length");
                    v = src[s++] & 255; match += v;
                    if (match > length) throw new IOException("Match overflow");
                } while (v == 255);
            }
            if (match > length - d) throw new IOException("Match bounds");
            for (int j = 0; j < match; j++) { dst[d] = dst[d-offset]; d++; }
        }
        if (d != length) throw new IOException("Decoded length mismatch");
        return dst;
    }

    private static void readFully(InputStream in, byte[] bytes) throws IOException {
        int pos = 0;
        while (pos < bytes.length) {
            int n = in.read(bytes, pos, bytes.length-pos);
            if (n <= 0) throw new IOException("Truncated block");
            pos += n;
        }
    }
    private static void skip(InputStream in, long n) throws IOException {
        if (n < 0) throw new IOException("Invalid frame order");
        while (n > 0) {
            long k = in.skip(n);
            if (k > 0) n -= k;
            else { if (in.read() < 0) throw new IOException("Truncated stream"); n--; }
        }
    }
}
