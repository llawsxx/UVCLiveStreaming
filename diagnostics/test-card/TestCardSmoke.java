/** Loads APK tests in a separate app_process; does not install or stop the main APP. */
public final class TestCardSmoke {
    public static void main(String[] args) throws Exception {
        String[][] tests = {
            {"TestCardGpuTest", "cardsHaveExpectedColorsPixelStripesMotionAndHud", "virtualFramesReachAvcAndHevcWithFractionalPts"},
            {"GpuVideoPipelineTest", "gpuUsesCorrectRangeOrientationAndBgrOrder", "selectableMatricesProduceReferenceRgbPixels"},
            {"VideoColorLutGpuTest", "disablingAndNeutralSettingsBypassExactlyAndBgrUsesRgbLut"}
        };
        for (String[] group : tests) {
            Class<?> type = Class.forName("com.llawsxx.uvclivestreaming.recording." + group[0]);
            Object instance = type.getConstructor().newInstance();
            for (int i = 1; i < group.length; i++) {
                try {
                    type.getMethod(group[i]).invoke(instance);
                    System.out.println("PASS " + group[0] + "." + group[i]);
                } catch (java.lang.reflect.InvocationTargetException error) {
                    error.getCause().printStackTrace();
                    System.exit(1);
                }
            }
        }
    }
}
