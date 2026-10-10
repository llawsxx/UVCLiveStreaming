/** Run in an independent app_process without replacing or stopping the main application. */
public final class AdaptiveBitrateSmoke {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("com.llawsxx.uvclivestreaming.recording.AdaptiveBitrateDeviceTest");
        try {
            type.getMethod("avcAndHevcChangeBitrateWithoutRestartThroughSurfaceAndYuv")
                .invoke(type.getConstructor().newInstance());
            System.out.println("PASS: dynamic bitrate, AVC/HEVC, Surface/YUV, CBR");
        } catch (java.lang.reflect.InvocationTargetException error) {
            error.getCause().printStackTrace();
            System.exit(1);
        }
    }
}
