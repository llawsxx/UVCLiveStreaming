public final class RawGpuSmoke {
    public static void main(String[] args) throws Exception {
        String[][] tests = args.length > 0 && args[0].startsWith("speed")
            ? new String[][] {{"RawGpuUploadTest", args[0].equals("speed4k") ? "benchmark4kRawLayouts" : "benchmarkRawLayouts"}}
            : new String[][] {
                {"RawGpuUploadTest", "nativeLayoutsMatchPlanarPixelsAtNativeAndScaledSizes",
                    "rawLayoutsReachAvcAndHevcWithCapturePts"},
                {"GpuVideoPipelineTest", "fullHeightChromaAndP010LowBitsAffectGpuRgb",
                    "rawDirectPipelinePreservesGpuPixelsLayoutAndRange",
                    "directBufferUploadsTheSamePixelsAndCanBeReusedAfterRender",
                    "gpuUsesCorrectRangeOrientationAndBgrOrder",
                    "selectableMatricesProduceReferenceRgbPixels",
                    "sourceRangeOverrideChangesPixelsIncludingNativeRgb",
                    "fiveFpsPreviewKeepsEveryEncoderSurfaceFrame"},
                {"TestCardGpuTest", "cardsHaveExpectedColorsPixelStripesMotionAndHud"},
                {"VideoColorLutGpuTest", "disablingAndNeutralSettingsBypassExactlyAndBgrUsesRgbLut"}
            };
        for (String[] group : tests) {
            Class<?> type = Class.forName("com.llawsxx.uvclivestreaming.recording." + group[0]);
            Object instance = type.getConstructor().newInstance();
            for (int index = 1; index < group.length; index++) {
                try {
                    type.getMethod(group[index]).invoke(instance);
                    System.out.println("PASS " + group[0] + "." + group[index]);
                } catch (java.lang.reflect.InvocationTargetException error) {
                    error.getCause().printStackTrace();
                    System.exit(1);
                }
            }
        }
    }
}
