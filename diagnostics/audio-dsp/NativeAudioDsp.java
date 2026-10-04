package com.llawsxx.uvclivestreaming.recording;

import java.util.Arrays;

/** Standalone app_process test of the APK's actual JNI PCM boundary. */
public final class NativeAudioDsp {
    private static native long create(int rate, int channels, boolean loudness, boolean limiter, float[] parameters);
    private static native int delayFrames(long handle);
    private static native void update(long handle, float[] parameters);
    private static native void process(long handle, byte[] bytes);
    private static native void destroy(long handle);
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        System.loadLibrary("uvclivestreaming_usb");
        float[] p = {0, -.5f, 80, -.5f, 1, 0, -16, 7, -1, 0, 5, 1000};
        long handle = create(48000, 2, false, false, p);
        check(handle != 0, "create bypass");
        try {
            byte[] pcm = new byte[65536 * 4];
            for (int i = 0; i < 65536; i++) {
                int value = i - 32768;
                pcm[i * 4] = (byte)value; pcm[i * 4 + 1] = (byte)(value >> 8);
                pcm[i * 4 + 2] = (byte)(-value); pcm[i * 4 + 3] = (byte)((-value) >> 8);
            }
            byte[] expected = pcm.clone();
            process(handle, pcm);
            check(Arrays.equals(expected, pcm), "all PCM16 values must survive float round trip");
            check(delayFrames(handle) == 0, "bypass delay");
        } finally { destroy(handle); }
        handle = create(48000, 1, false, true, p);
        check(handle != 0, "create limiter");
        try {
            check(delayFrames(handle) == 47, "actual limiter delay");
            p[0] = 12; p[3] = -6;
            update(handle, p);
            byte[] pcm = new byte[480 * 2];
            for (int i = 0; i < 480; i++) { pcm[i * 2] = -1; pcm[i * 2 + 1] = 127; }
            process(handle, pcm);
            int peak = 0;
            for (int i = 0; i < 480; i++) {
                short value = (short)((pcm[i * 2] & 255) | (pcm[i * 2 + 1] << 8));
                if (i < 47) check(value == 0, "startup padding");
                peak = Math.max(peak, Math.abs((int)value));
            }
            check(peak > 16000 && peak <= 16423, "limiter ceiling after PCM16 quantization: " + peak);
        } finally { destroy(handle); }
        System.out.println("JNI PASS: PCM16/float round trip, create/process/update/delay/destroy and limiter ceiling");
    }
}
