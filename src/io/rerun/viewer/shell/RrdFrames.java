package io.rerun.viewer.shell;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/** Boundary scanner for legacy RRF2 (Rerun 0.19) files. Never splits a data frame. */
public final class RrdFrames {
    private RrdFrames() { }

    // All frames remain intact. The uncompressed budget is a conservative WASM/Arrow bound.
    public static final long TARGET_BYTES = 32L * 1024 * 1024;
    public static final int SAMPLE_EVERY = 1;
    private static final int HEADER_BYTES = 12;
    private static final int FRAME_HEADER_BYTES = 8;
    private static final long MAX_FRAME_BYTES = 256L * 1024 * 1024;

    public static final class Segment {
        public final long start;
        public final long end;
        /** Encoded bytes emitted from this segment, including frame headers. */
        public final long sampledLength;
        /** Sum of uncompressed lengths of frames in this segment and its prefix. */
        public final long uncompressedLength;

        public Segment(long start, long end) {
            this(start, end, end - start, end - start);
        }

        private Segment(long start, long end, long sampledLength, long uncompressedLength) {
            this.start = start;
            this.end = end;
            this.sampledLength = sampledLength;
            this.uncompressedLength = uncompressedLength;
        }
    }

    /** Individual frame information for dynamic windowing. */
    public static final class FrameInfo {
        public final long fileOffset;     // Position in file
        public final long encodedSize;    // Compressed size + header (8 bytes)
        public final long uncompressedSize;
        public final int frameNumber;     // 0-based frame index
        
        FrameInfo(long fileOffset, long encodedSize, long uncompressedSize, int frameNumber) {
            this.fileOffset = fileOffset;
            this.encodedSize = encodedSize;
            this.uncompressedSize = uncompressedSize;
            this.frameNumber = frameNumber;
        }
    }

    public static final class Index {
        public final long preambleEnd;
        public final List<Segment> segments;
        public final List<FrameInfo> frames;  // All frames for dynamic windowing
        
        private Index(long preambleEnd, List<Segment> segments) {
            this(preambleEnd, segments, null);
        }
        
        private Index(long preambleEnd, List<Segment> segments, List<FrameInfo> frames) {
            this.preambleEnd = preambleEnd;
            this.segments = segments;
            this.frames = frames;
        }
    }

    private static long uint32(RandomAccessFile f) throws IOException {
        return (long) f.readUnsignedByte() | ((long) f.readUnsignedByte() << 8)
                | ((long) f.readUnsignedByte() << 16) | ((long) f.readUnsignedByte() << 24);
    }

    public static Index scan(File file) throws IOException {
        return scan(file, TARGET_BYTES);
    }

    public static Index scan(File file, long targetBytes) throws IOException {
        return scan(file, targetBytes, decompressionBudget(targetBytes));
    }

    public static Index scan(File file, long targetBytes, long targetUncompressedBytes)
            throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long size = f.length();
            if (size < HEADER_BYTES + FRAME_HEADER_BYTES || f.read() != 'R' || f.read() != 'R'
                    || f.read() != 'F' || f.read() != '2')
                throw new IOException("Not an RRF2 recording");
            f.seek(4);
            if (f.readUnsignedByte() != 0 || f.readUnsignedByte() != 0x13
                    || f.readUnsignedByte() != 0 || f.readUnsignedByte() != 0)
                throw new IOException("Only RRF2 version 0.19 is verified for segmentation");
            f.seek(HEADER_BYTES);
            List<Segment> frames = new ArrayList<Segment>();
            List<FrameInfo> frameInfos = new ArrayList<FrameInfo>();
            int frameNumber = 0;
            while (f.getFilePointer() < size) {
                long start = f.getFilePointer();
                if (size - start < FRAME_HEADER_BYTES)
                    throw new IOException("Truncated RRF2 frame header at " + start);
                long encoded = uint32(f);
                long uncompressed = uint32(f);
                if (encoded == 0 && uncompressed == 0 && f.getFilePointer() == size) break;
                if (uncompressed == 0 || encoded == 0 || encoded > MAX_FRAME_BYTES
                        || uncompressed > MAX_FRAME_BYTES || encoded > size - f.getFilePointer())
                    throw new IOException("Invalid RRF2 frame at " + start + ": "
                            + uncompressed + "/" + encoded);
                long end = f.getFilePointer() + encoded;
                frames.add(new Segment(start, end, end - start, uncompressed));
                frameInfos.add(new FrameInfo(start, end - start, uncompressed, frameNumber));
                f.seek(end);
                frameNumber++;
            }
            if (frames.size() < 2) throw new IOException("No data frames");
            return makeIndex(frames, frameInfos, targetBytes, targetUncompressedBytes);
        }
    }

    private static int readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int count = in.read(b, off + total, len - total);
            if (count < 0) throw new IOException("Unexpected end of RRF2 stream");
            total += count;
        }
        return total;
    }

    private static long uint32(InputStream in) throws IOException {
        byte[] b = new byte[4];
        readFully(in, b, 0, 4);
        return (long) (b[0] & 255) | ((long) (b[1] & 255) << 8)
                | ((long) (b[2] & 255) << 16) | ((long) (b[3] & 255) << 24);
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        byte[] buffer = null;
        while (count > 0) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Scan cancelled");
            long skipped = in.skip(count);
            if (skipped > 0) { count -= skipped; continue; }
            if (buffer == null) buffer = new byte[65536];
            int read = in.read(buffer, 0, (int) Math.min(count, buffer.length));
            if (read < 0) throw new IOException("Truncated RRF2 stream");
            count -= read;
        }
    }

    /** Scans a seek-free content URI stream without buffering the recording. */
    public static Index scan(InputStream in, long size) throws IOException {
        return scan(in, size, TARGET_BYTES);
    }

    public static Index scan(InputStream in, long size, long targetBytes) throws IOException {
        return scan(in, size, targetBytes, decompressionBudget(targetBytes));
    }

    public static Index scan(InputStream in, long size, long targetBytes,
                             long targetUncompressedBytes) throws IOException {
        byte[] magic = new byte[4];
        readFully(in, magic, 0, 4);
        if (magic[0] != 'R' || magic[1] != 'R' || magic[2] != 'F' || magic[3] != '2')
            throw new IOException("Not an RRF2 recording");
        byte[] version = new byte[4];
        readFully(in, version, 0, 4);
        skipFully(in, 4L);
        if ((version[0] & 255) != 0 || (version[1] & 255) != 0x13
                || version[2] != 0 || version[3] != 0)
            throw new IOException("Only RRF2 version 0.19 is verified for segmentation");
        long position = HEADER_BYTES;
        List<Segment> frames = new ArrayList<Segment>();
        List<FrameInfo> frameInfos = new ArrayList<FrameInfo>();
        int frameNumber = 0;
        while (position < size) {
            if (size - position < FRAME_HEADER_BYTES)
                throw new IOException("Truncated RRF2 frame header");
            long encoded = uint32(in);
            long uncompressed = uint32(in);
            position += FRAME_HEADER_BYTES;
            if (encoded == 0 && uncompressed == 0 && position == size) break;
            if (uncompressed == 0 || encoded == 0 || encoded > MAX_FRAME_BYTES
                    || uncompressed > MAX_FRAME_BYTES || encoded > size - position)
                throw new IOException("Invalid RRF2 frame at " + (position - FRAME_HEADER_BYTES));
            skipFully(in, encoded);
            long frameStart = position - FRAME_HEADER_BYTES;
            long frameEnd = position + encoded;
            frames.add(new Segment(frameStart, frameEnd,
                    encoded + FRAME_HEADER_BYTES, uncompressed));
            frameInfos.add(new FrameInfo(frameStart, encoded + FRAME_HEADER_BYTES, 
                    uncompressed, frameNumber));
            position += encoded;
            frameNumber++;
        }
        if (frames.size() < 2) throw new IOException("No data frames");
        return makeIndex(frames, frameInfos, targetBytes, targetUncompressedBytes);
    }

    private static Index makeIndex(List<Segment> frames, List<FrameInfo> frameInfos,
                                   long targetBytes, long targetUncompressedBytes) {
        if (targetBytes < 1L) targetBytes = TARGET_BYTES;
        if (targetUncompressedBytes < 1L) targetUncompressedBytes = decompressionBudget(targetBytes);
        long preambleEnd = frames.get(0).end;
        long preambleUncompressed = frames.get(0).uncompressedLength;
        List<Segment> parts = new ArrayList<Segment>();
        long partStart = frames.get(1).start;
        long partEnd = partStart;
        long partEncoded = 0L;
        long partUncompressed = 0L;
        for (int i = 1; i < frames.size(); i++) {
            Segment frame = frames.get(i);
            long candidateEncoded = preambleEnd + partEncoded + frame.sampledLength;
            long candidateUncompressed = preambleUncompressed + partUncompressed
                    + frame.uncompressedLength;
            if (partEnd > partStart
                    && (candidateEncoded > targetBytes
                    || candidateUncompressed > targetUncompressedBytes)) {
                parts.add(new Segment(partStart, partEnd, partEncoded, 
                        preambleUncompressed + partUncompressed));
                partStart = frame.start;
                partEnd = partStart;
                partEncoded = 0L;
                partUncompressed = 0L;
            }
            partEnd = frame.end;
            partEncoded += frame.sampledLength;
            partUncompressed += frame.uncompressedLength;
        }
        parts.add(new Segment(partStart, partEnd, partEncoded,
                preambleUncompressed + partUncompressed));
        return new Index(preambleEnd, parts, frameInfos);
    }

    private static long decompressionBudget(long targetBytes) {
        if (targetBytes < 1L) targetBytes = TARGET_BYTES;
        if (targetBytes > Long.MAX_VALUE / 4L) return Long.MAX_VALUE;
        return targetBytes * 4L;
    }

    public static InputStream open(File file, Index index, int part) throws IOException {
        if (part < 0 || part >= index.segments.size()) throw new IOException("Invalid segment");
        return new SegmentedStream(new FileInputStream(file), index.preambleEnd,
                index.segments.get(part));
    }

    /** Sequentially reads the file header + metadata, then one complete segment. */
    public static InputStream open(InputStream source, Index index, int part) throws IOException {
        if (part < 0 || part >= index.segments.size()) throw new IOException("Invalid segment");
        return new SegmentedStream(source, index.preambleEnd, index.segments.get(part));
    }

    private static final class SegmentedStream extends InputStream {
        private final InputStream source;
        private final long preambleEnd;
        private final Segment segment;
        private long sourcePosition;
        private long remainingPrefix;
        private long remainingSegment;
        private boolean moved;
        private int terminatorPosition;
        private final byte[] terminator = new byte[8];

        SegmentedStream(InputStream source, long preambleEnd, Segment segment) {
            this.source = source;
            this.preambleEnd = preambleEnd;
            this.segment = segment;
            this.remainingPrefix = preambleEnd;
            this.remainingSegment = segment.end - segment.start;
        }

        @Override public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (b == null) throw new NullPointerException();
            if (off < 0 || len < 0 || off > b.length - len)
                throw new IndexOutOfBoundsException();
            if (len == 0) return 0;
            if (remainingPrefix > 0) {
                int count = readRaw(b, off, (int) Math.min(len, remainingPrefix));
                remainingPrefix -= count;
                return count;
            }
            if (!moved) {
                skipFully(source, segment.start - sourcePosition);
                sourcePosition = segment.start;
                moved = true;
            }
            if (remainingSegment > 0) {
                int count = readRaw(b, off, (int) Math.min(len, remainingSegment));
                remainingSegment -= count;
                return count;
            }
            if (terminatorPosition < terminator.length) {
                int count = Math.min(len, terminator.length - terminatorPosition);
                System.arraycopy(terminator, terminatorPosition, b, off, count);
                terminatorPosition += count;
                return count;
            }
            return -1;
        }

        private int readRaw(byte[] b, int off, int len) throws IOException {
            int count = source.read(b, off, len);
            if (count < 0) throw new IOException("Truncated RRF2 segment");
            sourcePosition += count;
            return count;
        }

        @Override public void close() throws IOException { source.close(); }
    }

    /**
     * Stream that serves RRD preamble + selected frame range.
     * Used for dynamic windowing to serve only a subset of frames.
     */
    static final class FrameWindowStream extends InputStream {
        private final InputStream source;
        private final Index index;
        private final int frameStart;
        private final int frameEnd;
        
        private long position = 0;
        private long preambleEnd;
        private List<FrameInfo> selectedFrames;
        private int currentFrameIndex = 0;
        private long currentFrameEnd = 0;
        private int terminatorPosition = 0;
        private final byte[] terminator = new byte[8];
        
        FrameWindowStream(InputStream source, Index index, int frameStart, int frameEnd) {
            if (source == null || index == null) throw new NullPointerException();
            int dataFrameCount = Math.max(0, index.frames.size() - 1);
            if (frameStart < 0 || frameEnd < frameStart || frameEnd > dataFrameCount)
                throw new IllegalArgumentException("Invalid frame window " + frameStart + ".." + frameEnd);
            this.source = source;
            this.index = index;
            this.frameStart = frameStart;
            this.frameEnd = frameEnd;
            this.preambleEnd = index.preambleEnd;
            
            // Frame 0 is the metadata frame and is already part of the preamble.
            // Window indexes are logical data-frame indexes, so map them to the
            // entries after the metadata frame.
            this.selectedFrames = new ArrayList<FrameInfo>();
            for (int i = frameStart; i < frameEnd; i++) {
                selectedFrames.add(index.frames.get(i + 1));
            }
            
            if (!selectedFrames.isEmpty()) {
                currentFrameEnd = selectedFrames.get(0).fileOffset + selectedFrames.get(0).encodedSize;
            }
        }
        
        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int result = read(b, 0, 1);
            return result == 1 ? (b[0] & 0xFF) : -1;
        }
        
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (buffer == null) throw new NullPointerException();
            if (offset < 0 || length < 0 || offset > buffer.length - length)
                throw new IndexOutOfBoundsException();
            if (length == 0) return 0;
            
            // Phase 1: Serve preamble
            if (position < preambleEnd) {
                int toRead = (int) Math.min(length, preambleEnd - position);
                int actual = source.read(buffer, offset, toRead);
                if (actual < 0) throw new IOException("Truncated RRF2 window preamble");
                if (actual > 0) position += actual;
                return actual;
            }
            
            // Phase 2: Serve selected frames
            if (currentFrameIndex >= selectedFrames.size()) {
                if (terminatorPosition < terminator.length) {
                    int count = Math.min(length, terminator.length - terminatorPosition);
                    System.arraycopy(terminator, terminatorPosition, buffer, offset, count);
                    terminatorPosition += count;
                    return count;
                }
                return -1;
            }
            
            FrameInfo frame = selectedFrames.get(currentFrameIndex);
            
            // Skip to frame start if needed
            if (position < frame.fileOffset) {
                long toSkip = frame.fileOffset - position;
                long skipped = source.skip(toSkip);
                if (skipped > 0) position += skipped;
                if (position < frame.fileOffset) {
                    // Can't skip, read and discard
                    byte[] temp = new byte[4096];
                    while (position < frame.fileOffset) {
                        int chunk = (int) Math.min(temp.length, frame.fileOffset - position);
                        int readBytes = source.read(temp, 0, chunk);
                        if (readBytes < 0) throw new IOException("Truncated RRF2 window gap");
                        position += readBytes;
                    }
                }
            }
            
            // Read frame data
            if (position >= frame.fileOffset && position < currentFrameEnd) {
                int toRead = (int) Math.min(length, currentFrameEnd - position);
                int actual = source.read(buffer, offset, toRead);
                if (actual < 0) throw new IOException("Truncated RRF2 window frame");
                if (actual > 0) {
                    position += actual;
                    
                    // Move to next frame if current is complete
                    if (position >= currentFrameEnd) {
                        currentFrameIndex++;
                        if (currentFrameIndex < selectedFrames.size()) {
                            FrameInfo nextFrame = selectedFrames.get(currentFrameIndex);
                            currentFrameEnd = nextFrame.fileOffset + nextFrame.encodedSize;
                        }
                    }
                }
                return actual;
            }
            
            return -1;
        }
        
        @Override
        public void close() throws IOException {
            source.close();
        }
    }
}
