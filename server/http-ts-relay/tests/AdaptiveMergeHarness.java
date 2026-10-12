// Production single-store uploader/controller: fast upload, throttled store-to-merge downloads.
import com.llawsxx.uvclivestreaming.recording.HttpAdaptiveBitrateController;
import com.llawsxx.uvclivestreaming.recording.HttpTsUploadSink;
import com.llawsxx.uvclivestreaming.recording.HttpUploadStats;
import com.llawsxx.uvclivestreaming.recording.HttpMergeFeedback;
import kotlin.Unit;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public class AdaptiveMergeHarness {
    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        HttpAdaptiveBitrateController controller = new HttpAdaptiveBitrateController(6_000_000, 1_000_000, 0);
        HttpTsUploadSink sink = new HttpTsUploadSink(args[0], 60,
            message -> { System.out.println(message); return Unit.INSTANCE; });
        boolean receivedBacklog = false, reducedWithFastUpload = false;
        int targetAtEnd;
        try {
            long start = System.nanoTime();
            for (int second = 0; second < 22; second++) {
                long due = start + second * 1_000_000_000L;
                while (System.nanoTime() < due) Thread.sleep(5);
                HttpUploadStats stats = sink.getStats();
                HttpMergeFeedback feedback = stats.getServers().get(0).getMergeFeedback();
                receivedBacklog |= feedback != null && feedback.getPendingDurationUs() >= 3_000_000;
                HttpAdaptiveBitrateController.Adjustment change = controller.sample(System.nanoTime(), stats, controller.getTarget());
                if (change != null) {
                    System.out.println("second=" + second + " target=" + change.getBitrate() + " reason=" + change.getReason());
                    if (change.getBitrate() < 6_000_000 && stats.getUnacknowledgedDurationUs() <= 1_500_000 &&
                        change.getReason().contains("merge")) reducedWithFastUpload = true;
                }
                int packets = Math.max(1, controller.getTarget() / (8 * 188));
                byte[] block = new byte[188 * packets];
                for (int i = 0; i < block.length; i++) block[i] = (byte) (i % 188 == 0 ? 0x47 : second + 1);
                sink.write(block);
            }
            targetAtEnd = controller.getTarget();
        } finally { sink.close(); }
        if (!receivedBacklog) throw new AssertionError("No real merge backlog reached single-store upload ACKs");
        if (!reducedWithFastUpload) throw new AssertionError("Merge congestion did not reduce bitrate with a healthy phone upload queue");
        if (targetAtEnd > 3_000_000) throw new AssertionError("Bitrate did not fit the 3 Mbps download link: " + targetAtEnd);
        System.out.println("PASS: single-store fast uploads adapt to a slower store-to-merge link");
    }
}
