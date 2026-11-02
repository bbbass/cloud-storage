import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Burst producer for the async pipeline stress test.
 *
 * It publishes task messages with the same rocketmq-client the application uses,
 * so the measured rate is the broker ingest rate for this topic. The matching rows
 * in processing_tasks are seeded by scripts/stress/mq-peak.mjs (the consumer still
 * reads the task from the DB, exactly like in production).
 *
 * Run (classpath = extracted BOOT-INF/lib of the boot jar):
 *   java -cp "target/stress-libs/*" scripts/stress/MqBurstProducer.java \
 *        --ids=ids.txt --threads=32
 * Line format of the ids file: taskId fileId taskType
 */
public class MqBurstProducer {

    public static void main(String[] args) throws Exception {
        System.setProperty("rocketmq.client.logUseSlf4j", "true");

        String namesrv = "localhost:9876";
        String topic = "cloud-storage-processing";
        String tag = "processing";
        String group = "cloud-storage-stress-producer";
        String idsFile = null;
        int threads = 32;

        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (!arg.startsWith("--") || eq < 0) {
                continue;
            }
            String key = arg.substring(2, eq);
            String value = arg.substring(eq + 1);
            switch (key) {
                case "namesrv" -> namesrv = value;
                case "topic" -> topic = value;
                case "tag" -> tag = value;
                case "group" -> group = value;
                case "ids" -> idsFile = value;
                case "threads" -> threads = Integer.parseInt(value);
                default -> { }
            }
        }
        if (idsFile == null) {
            System.err.println("missing --ids=<file>");
            System.exit(2);
        }

        List<String> lines = Files.readAllLines(Path.of(idsFile), StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        if (lines.isEmpty()) {
            System.err.println("no task ids in " + idsFile);
            System.exit(2);
        }

        DefaultMQProducer producer = new DefaultMQProducer(group);
        producer.setNamesrvAddr(namesrv);
        producer.setSendMsgTimeout(5000);
        producer.setRetryTimesWhenSendFailed(2);
        producer.start();
        System.out.println("producer started: namesrv=" + namesrv + ", topic=" + topic + ", messages=" + lines.size());

        // copies so the worker lambdas can capture them (the originals are reassigned above)
        final String targetTopic = topic;
        final String targetTag = tag;

        // Warmup: the first send pays route discovery and connection setup.
        // Timing it would understate the achievable send rate by an order of magnitude.
        long warmupStartedAt = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            producer.send(new Message(targetTopic, targetTag, "warmup",
                    "{\"taskId\":0}".getBytes(StandardCharsets.UTF_8)));
        }
        long warmupMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - warmupStartedAt);
        System.out.println("warmup done: 5 messages in " + warmupMs + " ms");

        AtomicInteger next = new AtomicInteger();
        AtomicInteger produced = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        long startedAt = System.nanoTime();
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                while (true) {
                    int index = next.getAndIncrement();
                    if (index >= lines.size()) {
                        return;
                    }
                    String[] parts = lines.get(index).split("\\s+");
                    String taskId = parts[0];
                    String fileId = parts.length > 1 ? parts[1] : "0";
                    String taskType = parts.length > 2 ? parts[2] : "THUMBNAIL";
                    String body = "{\"taskId\":" + taskId + ",\"fileId\":" + fileId
                            + ",\"taskType\":\"" + taskType + "\"}";
                    try {
                        Message message = new Message(targetTopic, targetTag, fileId,
                                body.getBytes(StandardCharsets.UTF_8));
                        SendResult sendResult = producer.send(message);
                        if (sendResult.getSendStatus() != null) {
                            produced.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    }
                }
            }, "stress-producer-" + t);
            workers[t].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        double rate = elapsedMs == 0 ? 0d : Math.round(produced.get() * 100000.0 / elapsedMs) / 100.0;

        System.out.println("RESULT mq-burst-producer {\"produced\":" + produced.get()
                + ",\"failed\":" + failed.get()
                + ",\"elapsedMs\":" + elapsedMs
                + ",\"ratePerSec\":" + rate + "}");

        producer.shutdown();
    }
}
