/** Runs GPU tests independently; does not install or stop the capture APP. */
public final class VideoColorLutSmoke {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("com.llawsxx.uvclivestreaming.recording.VideoColorLutGpuTest");
        Object instance = type.getConstructor().newInstance();
        String[] methods = args.length > 0 ? args : new String[] {
            "atlasMatchesReferenceFor33And65AndSharesPreviewEncoderOutput",
            "disablingAndNeutralSettingsBypassExactlyAndBgrUsesRgbLut",
            "yuvAndP010EnterTheSameLutAfterRangeConversion"
        };
        for (String method : methods) {
            try {
                type.getMethod(method).invoke(instance);
                System.out.println("PASS " + method);
            } catch (java.lang.reflect.InvocationTargetException error) {
                error.getCause().printStackTrace();
                System.exit(1);
            }
        }
    }
}
