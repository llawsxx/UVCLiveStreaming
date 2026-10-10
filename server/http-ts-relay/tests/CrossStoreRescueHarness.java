import com.llawsxx.uvclivestreaming.recording.DistributedHttpTsUploadWorker;
import com.llawsxx.uvclivestreaming.recording.TsUploadBlock;
import kotlin.Unit;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

// Drive the real phone distributor: upload a known missing middle block to B,
// then let merge recover it through A without being able to query B's directory.
public class CrossStoreRescueHarness {
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Timed out");
            Thread.sleep(20);
        }
    }
    private static void rate(String url, long rate) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)URI.create(url.replace("/upload/", "/feedback/")).toURL().openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestProperty("X-Download-Rate-Bps", Long.toString(rate));
        try {
            if (connection.getResponseCode() != 200) throw new AssertionError("Feedback failed");
            connection.getInputStream().close();
        } finally { connection.disconnect(); }
    }
    private static void select(DistributedHttpTsUploadWorker worker, String[] urls, int destination) throws Exception {
        // Repeated feedback converges the store's rising-rate smoothing before enqueueing.
        for (int i = 0; i < 32; i++) {
            rate(urls[destination], 1_000_000);
            rate(urls[1 - destination], 1000);
        }
        await(() -> worker.snapshot().getServers().get(destination).getEstimatedBitsPerSecond() != null &&
            worker.snapshot().getServers().get(destination).getEstimatedBitsPerSecond() > 7_000_000 &&
            worker.snapshot().getServers().get(1 - destination).getEstimatedBitsPerSecond() != null &&
            worker.snapshot().getServers().get(1 - destination).getEstimatedBitsPerSecond() == 8000);
    }
    public static void main(String[] args) throws Exception {
        rate(args[0], 1_000_000);
        rate(args[1], 1000);
        DistributedHttpTsUploadWorker worker = new DistributedHttpTsUploadWorker(Arrays.asList(args), 60,
            message -> { System.out.println(message); return Unit.INSTANCE; });
        long epoch = System.currentTimeMillis();
        try {
            for (int sequence = 0; sequence < 3; sequence++) {
                select(worker, args, sequence == 1 ? 1 : 0);
                byte[] data = new byte[188 * 128];
                for (int i = 0; i < data.length; i++) {
                    int offset = i % 188;
                    data[i] = (byte)(offset == 0 ? 0x47 : offset == 1 ? 0x1F :
                        offset == 2 ? 0xFE : offset == 3 ? 0x10 : sequence + 1);
                }
                worker.enqueue(new TsUploadBlock("cross-store-test", sequence, 1_000_000, false, data,
                    System.nanoTime(), sequence * 1_000_000L, epoch), 60);
                long expected = (sequence + 1L) * data.length;
                await(() -> worker.getBytesAcknowledged().get() == expected);
            }
            System.out.println("READY: 0,2 on A; 1 on B");
            System.out.flush();
            await(() -> worker.snapshot().getRedirectAcknowledged() == 1);
            if (worker.getBytesAcknowledged().get() != 3L * 188 * 128)
                throw new AssertionError("Duplicate ACK inflated unique bytes");
            System.out.println("PASS: cross-store rescue, unique bytes=" + worker.getBytesAcknowledged().get());
        } finally { worker.close(); }
    }
}
