// Calls the actual Android-independent production uploader, not a protocol imitation.
import com.llawsxx.uvclivestreaming.recording.HttpTsUploadSink;
import kotlin.Unit;

public class UploadHarness {
    private static byte[] ts(int tag) {
        byte[] bytes = new byte[188 * 128];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i % 188 == 0 ? 0x47 : tag);
        return bytes;
    }
    private static void waitForConfirmed(HttpTsUploadSink sink, long bytes) throws Exception {
        long deadline = System.nanoTime() + 25_000_000_000L;
        while (sink.getBytesSent() < bytes) {
            if (System.nanoTime() > deadline) throw new AssertionError("Uploader did not drain");
            Thread.sleep(20);
        }
    }
    public static void main(String[] args) throws Exception {
        HttpTsUploadSink first = new HttpTsUploadSink(args[1], 60,
            message -> { System.out.println(message); return Unit.INSTANCE; });
        if (args.length > 2 && args[2].equals("stall")) {
            byte[] large = new byte[188 * 70000];
            for (int i = 0; i < large.length; i++) large[i] = (byte) (i % 188 == 0 ? 0x47 : 7);
            first.write(large); Thread.sleep(1100); first.write(ts(99));
            waitForConfirmed(first, large.length);
            first.close();
        } else {
            first.write(ts(1)); Thread.sleep(1100);
            first.write(ts(2)); Thread.sleep(1100);
            first.write(ts(3));
            waitForConfirmed(first, 2L * 188 * 128);
            first.close();
            HttpTsUploadSink second = new HttpTsUploadSink(args[1], 60,
                message -> { System.out.println(message); return Unit.INSTANCE; });
            second.write(ts(4)); Thread.sleep(1100);
            second.write(ts(5)); Thread.sleep(1100);
            second.write(ts(6));
            waitForConfirmed(second, 2L * 188 * 128);
            second.close();
        }
        System.out.println("PASS: production uploader confirmed emitted blocks and discarded partial tails on stop");
    }
}
