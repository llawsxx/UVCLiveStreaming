/** Standalone APK/native PCM tests; no installation, camera, microphone or real USB access. */
public final class UacSmoke {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("com.llawsxx.uvclivestreaming.recording.UsbWidePcmGpuTest");
        Object tests = type.getConstructor().newInstance();
        for (String method : new String[] {"nativeDspPreserves24And32BitInputBelowPcm16Resolution",
                "nativeWideConversionHasCorrectFullScaleAndMatches16BitRoundTrip",
                "audioOnlyRejectsInvalidDescriptorWithoutOpeningVideo"}) {
            try {
                type.getMethod(method).invoke(tests);
                System.out.println("PASS " + method);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                failure.getCause().printStackTrace();
                System.exit(1);
            }
        }
    }
}
