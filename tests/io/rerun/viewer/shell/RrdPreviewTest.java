package io.rerun.viewer.shell;

import java.io.*;
import java.util.Arrays;

public final class RrdPreviewTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    private static void bad(byte[] b, int n) throws Exception {
        try { RrdPreview.decodeLz4(b,n); throw new AssertionError("accepted invalid block"); }
        catch (IOException expected) { checks++; }
    }
    private static byte[] literal(byte[] data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Math.min(data.length,15) << 4);
        if (data.length >= 15) {
            int n = data.length - 15;
            while (n >= 255) { out.write(255); n -= 255; }
            out.write(n);
        }
        out.write(data); return out.toByteArray();
    }
    private static void uint32(OutputStream out,int n) throws Exception {
        for(int i=0;i<4;i++) out.write(n >>> (8*i));
    }
    private static void frame(OutputStream out, String text) throws Exception {
        byte[] raw=text.getBytes("UTF-8"), encoded=literal(raw);
        uint32(out,encoded.length);uint32(out,raw.length);out.write(encoded);
    }
    public static void main(String[] args) throws Exception {
        byte[] text="EncodedImageIndicator".getBytes("UTF-8");
        check(Arrays.equals(text,RrdPreview.decodeLz4(literal(text),text.length)));
        check(Arrays.equals("aaaaa".getBytes("UTF-8"),RrdPreview.decodeLz4(new byte[]{16,97,1,0},5)));
        byte[] big=new byte[1024]; Arrays.fill(big,(byte)42);
        check(Arrays.equals(big,RrdPreview.decodeLz4(literal(big),big.length)));
        bad(new byte[]{(byte)240},15); bad(new byte[]{32,1},2);
        bad(new byte[]{16,1,0,0},5);bad(new byte[]{16,1,2,0},5);
        bad(new byte[]{16,1,1},5);bad(new byte[]{31,1,1,0},20);
        bad(new byte[]{16,1,1,0},4);bad(new byte[]{16,1},2);
        bad(new byte[0],RrdPreview.MAX_BLOCK+1);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(new byte[]{'R','R','F','2',0,19,0,0,1,1,0,0});
        frame(out,"metadata");frame(out,"diagnostics");frame(out,"EncodedImageIndicator");frame(out,"Boxes2DIndicator");
        out.write(new byte[8]);byte[] recording=out.toByteArray();
        RrdFrames.Index index=RrdFrames.scan(new ByteArrayInputStream(recording),recording.length,1,1);
        check(RrdPreview.findSegment(new ByteArrayInputStream(recording),index,false)==1);
        check(RrdPreview.findSegment(new ByteArrayInputStream(recording),index,true)==2);
        check(RrdPreview.findMarkerFrames(new ByteArrayInputStream(recording), index, false)
                .equals(java.util.Collections.singletonList(2)));
        check(RrdPreview.findMarkerFrames(new ByteArrayInputStream(recording), index, true)
                .equals(java.util.Collections.singletonList(3)));
        // Unknown compression must not guess.
        recording[8]=7;
        check(RrdPreview.findSegment(new ByteArrayInputStream(recording),index,false)==-1);
        System.out.println("PASS RrdPreviewTest: " + checks + " checks");
        if(args.length>0) {
            File file=new File(args[0]);RrdFrames.Index real=RrdFrames.scan(file,4L*1024*1024);
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            long decodedBytes=0;
            try(RandomAccessFile input=new RandomAccessFile(file,"r")) {
                for(RrdFrames.FrameInfo f:real.frames) {
                    input.seek(f.fileOffset+8);
                    byte[] encoded=new byte[(int)f.encodedSize-8];input.readFully(encoded);
                    byte[] decoded=RrdPreview.decodeLz4(encoded,(int)f.uncompressedSize);
                    digest.update(decoded);decodedBytes+=decoded.length;
                }
            }
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format("%02x",b&255));
            System.out.println("Java LZ4 all frames: bytes="+decodedBytes+" sha256="+hex);
            for(boolean boxes:new boolean[]{false,true}) {
                try(InputStream in=new FileInputStream(file)) {
                    int part=RrdPreview.findSegment(in,real,boxes);
                    if(part<0) throw new AssertionError("No marker located in fixture");
                    System.out.println("Real marker probe boxes="+boxes+" part="+(part+1));
                }
            }
            try (InputStream in = new FileInputStream(file)) {
                java.util.List<Integer> imageFrames = RrdPreview.findMarkerFrames(in, real, false);
                if (imageFrames.size() < 2) throw new AssertionError("Too few real image frames");
                System.out.println("Real image marker frames=" + imageFrames.size()
                        + " first=" + imageFrames.get(0) + " last=" + imageFrames.get(imageFrames.size()-1));
            }
        }
    }
}
