import com.llawsxx.uvclivestreaming.recording.DistributedHttpTsUploadWorker;
import com.llawsxx.uvclivestreaming.recording.TsUploadBlock;
import kotlin.Unit;
import java.util.Arrays;

// Uses the production distributor, including feedback polling, pacing and acknowledged-block rescue.
public class DistributedUploadHarness {
    public static void main(String[] args) throws Exception {
        DistributedHttpTsUploadWorker worker = new DistributedHttpTsUploadWorker(Arrays.asList(args[0], args[1]), 60,
            message -> { System.out.println(message); return Unit.INSTANCE; });
        long epoch = System.currentTimeMillis();
        int count = Integer.parseInt(args[2]), packets = Integer.parseInt(args[3]);
        long start = System.nanoTime();
        try {
            for (int sequence = 0; sequence < count; sequence++) {
                byte[] data = new byte[188 * packets];
                for (int i = 0; i < data.length; i++) data[i] = (byte)(i % 188 == 0 ? 0x47 : sequence + 1);
                worker.enqueue(new TsUploadBlock("distributed-test", sequence, 1_000_000, false, data,
                    System.nanoTime(), sequence * 1_000_000L, epoch), 60);
                long due = start + (sequence + 1) * 1_000_000_000L;
                while (System.nanoTime() < due) Thread.sleep(5);
            }
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (worker.getBytesAcknowledged().get() < (long)count * 188 * packets) {
                if (System.nanoTime() > deadline) throw new AssertionError("Upload did not drain");
                Thread.sleep(20);
            }
            // Keep rescue history alive while the downstream finishes or requests a slow-block replica.
            Thread.sleep(7_000);
            if (worker.getBytesAcknowledged().get() != (long)count * 188 * packets)
                throw new AssertionError("Duplicate ACK inflated unique byte count");
            System.out.println("PASS: production distributor drained; unique bytes=" + worker.getBytesAcknowledged().get());
        } finally { worker.close(); }
        if (worker.getPendingBlocks() != 0) throw new AssertionError("Stop did not clear memory queue");
    }
}
