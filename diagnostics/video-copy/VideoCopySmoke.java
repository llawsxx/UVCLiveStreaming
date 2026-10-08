import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.util.*;

/** Runs the built APK's actual Kotlin classes and JNI library in a standalone ART process. */
public final class VideoCopySmoke {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static byte[] read(File file) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) >= 0;) output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }
    static ByteBuffer direct(byte[] bytes) {
        ByteBuffer memory = ByteBuffer.allocateDirect(bytes.length + 16);
        memory.position(8); memory.put(bytes); memory.position(8); memory.limit(8 + bytes.length);
        return memory.slice();
    }
    static byte[] contents(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.capacity()]; ByteBuffer view = buffer.duplicate(); view.clear(); view.get(bytes); return bytes;
    }
    public static void main(String[] args) throws Exception {
        Class<?> nativeClass = Class.forName("com.llawsxx.uvclivestreaming.recording.NativeUsbCapture");
        Object capture = nativeClass.getField("INSTANCE").get(null);
        Method oldRaw = nativeClass.getMethod("nativeConvertRawToGpuBuffer", byte[].class, int.class, int.class, int.class, ByteBuffer.class);
        Method raw = nativeClass.getMethod("nativeConvertRawBufferToGpuBuffer", ByteBuffer.class, int.class, int.class, int.class, int.class, ByteBuffer.class);
        for (int format : new int[] {2,3,4,5,6,7,9}) {
            int size = format == 2 || format == 3 ? 8 : format == 4 || format == 7 || format == 9 ? 12 : 6;
            byte[] bytes = new byte[size]; for (int i = 0; i < size; ++i) bytes[i] = (byte)(i * 17 + 9);
            ByteBuffer reference = ByteBuffer.allocateDirect(size);
            ByteBuffer actual = ByteBuffer.allocateDirect(size);
            ByteBuffer source = direct(bytes);
            check((Boolean)oldRaw.invoke(capture, bytes, format, 2, 2, reference));
            check((Boolean)raw.invoke(capture, source.asReadOnlyBuffer(), size, format, 2, 2, actual));
            check(Arrays.equals(contents(reference), contents(actual)));
            check(Arrays.equals(bytes, contents(source)));
            check(!(Boolean)raw.invoke(capture, source, size - 1, format, 2, 2, actual));
            check(!(Boolean)raw.invoke(capture, source, size + 1, format, 2, 2, actual));
            check(!(Boolean)raw.invoke(capture, ByteBuffer.wrap(bytes), size, format, 2, 2, actual));
            check(!(Boolean)raw.invoke(capture, source, size, format, 2, 2, actual.asReadOnlyBuffer()));
        }
        Method oldJpeg = nativeClass.getMethod("nativeDecodeMjpegToYuv", byte[].class, int.class, int.class, int.class, int.class, ByteBuffer.class);
        Method jpeg = nativeClass.getMethod("nativeDecodeMjpegBufferToYuv", ByteBuffer.class, int.class, int.class, int.class, int.class, int.class, ByteBuffer.class);
        Class<?> geometry = Class.forName("com.llawsxx.uvclivestreaming.recording.MjpegChromaGeometry");
        Method readGeometry = geometry.getMethod("read", ByteBuffer.class, int.class, int.class);
        for (String sampling : new String[] {"420","422","444"}) {
            byte[] original = read(new File(args[0], "yuv" + sampling + ".jpg"));
            int cw = sampling.equals("444") ? 32 : 16;
            int ch = sampling.equals("420") ? 12 : 24;
            int size = 32 * 24 + 2 * cw * ch;
            ByteBuffer reference = ByteBuffer.allocateDirect(size);
            check((Boolean)oldJpeg.invoke(capture, original, 32, 24, cw, ch, reference));
            byte[] prefix = new byte[original.length + 3]; System.arraycopy(original, 0, prefix, 3, original.length);
            for (byte[] bytes : new byte[][] {original, prefix, Arrays.copyOf(original, original.length - 2), Arrays.copyOfRange(original, 2, original.length)}) {
                ByteBuffer source = direct(bytes);
                Object dimensions = readGeometry.invoke(geometry.getField("INSTANCE").get(null), source, 32, 24);
                check(dimensions != null);
                check((Integer)dimensions.getClass().getMethod("getFirst").invoke(dimensions) == cw);
                check((Integer)dimensions.getClass().getMethod("getSecond").invoke(dimensions) == ch);
                ByteBuffer output = ByteBuffer.allocateDirect(size + 16);
                for (int i = 0; i < output.capacity(); ++i) output.put(i, (byte)0x5a);
                output.position(8); output.limit(8 + size); ByteBuffer target = output.slice();
                check((Boolean)jpeg.invoke(capture, source.asReadOnlyBuffer(), bytes.length, 32, 24, cw, ch, target));
                check(Arrays.equals(contents(reference), contents(target)));
                output.clear();
                for (int i = 0; i < 8; ++i) check(output.get(i) == 0x5a && output.get(size + 8 + i) == 0x5a);
                check(Arrays.equals(bytes, contents(source)));
                check(!(Boolean)jpeg.invoke(capture, source, bytes.length + 1, 32, 24, cw, ch, target));
                check(!(Boolean)jpeg.invoke(capture, source, bytes.length, 32, 24, cw, ch, ByteBuffer.allocateDirect(size - 1)));
                check(!(Boolean)jpeg.invoke(capture, source, bytes.length, 32, 24, cw, ch, target.asReadOnlyBuffer()));
                check(!(Boolean)jpeg.invoke(capture, source, bytes.length, 32, 24, cw - 1, ch, target));
            }
        }
        Class<?> bufferClass = Class.forName("com.llawsxx.uvclivestreaming.recording.CapturedVideoBuffer");
        Class.forName("com.llawsxx.uvclivestreaming.recording.UsbCaptureCallback").getMethod("onUsbVideoFrame",
            bufferClass, int.class, int.class, int.class, long.class);
        Object emptyLease = bufferClass.getConstructor(ByteBuffer.class, long.class).newInstance(ByteBuffer.allocateDirect(1), 0L);
        bufferClass.getMethod("close").invoke(emptyLease);
        bufferClass.getMethod("close").invoke(emptyLease);
        System.out.println("APK JNI smoke tests passed: 7 raw formats, 12 JPEG/repaired variants, buffer bounds and callback ABI");
    }
}
