/** Runs encoder integration tests in an independent process without stopping the main app. */
public final class EncoderSettingsSmoke {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("com.llawsxx.uvclivestreaming.recording.VideoEncoderSettingsDeviceTest");
        try {
            type.getMethod("explicitRequestsEncodeAvcAndHevcThroughSurfaceAndYuv").invoke(type.getConstructor().newInstance());
            System.out.println("PASS: explicit encoder settings, AVC/HEVC Surface/YUV");
        } catch (java.lang.reflect.InvocationTargetException error) {
            error.getCause().printStackTrace();
            System.exit(1);
        }
    }
}
