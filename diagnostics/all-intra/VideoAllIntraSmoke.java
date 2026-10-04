/** Runs the APK Surface encoder test in an independent process without installation. */
public final class VideoAllIntraSmoke {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("com.llawsxx.uvclivestreaming.recording.VideoAllIntraTest");
        try {
            type.getMethod("everySurfaceFrameIsAKeyFrameForH264AndHevc").invoke(type.getConstructor().newInstance());
        } catch (java.lang.reflect.InvocationTargetException error) {
            error.getCause().printStackTrace();
            System.exit(1);
        }
        System.out.println("PASS everySurfaceFrameIsAKeyFrameForH264AndHevc");
    }
}
