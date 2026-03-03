package ch.unisg.kafka.experiments.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MetricsCollector {

    private final String label;
    private final CopyOnWriteArrayList<Long> latenciesMs = new CopyOnWriteArrayList<>();
    private long startTimeMs;
    private long endTimeMs;
    private long messagesSent;
    private long messagesFailed;

    public MetricsCollector(String label) {
        this.label = label;
    }

    public void start() {
        this.startTimeMs = System.currentTimeMillis();
    }

    public void stop() {
        this.endTimeMs = System.currentTimeMillis();
    }

    public void recordLatency(long latencyMs) {
        latenciesMs.add(latencyMs);
    }

    public synchronized void incrementSent() {
        messagesSent++;
    }

    public synchronized void incrementFailed() {
        messagesFailed++;
    }

    public synchronized long getMessagesSent() {
        return messagesSent;
    }

    public synchronized long getMessagesFailed() {
        return messagesFailed;
    }

    public double getElapsedSeconds() {
        return (endTimeMs - startTimeMs) / 1000.0;
    }

    public double getThroughput() {
        double elapsed = getElapsedSeconds();
        return elapsed > 0 ? messagesSent / elapsed : 0;
    }

    public long getAvgLatency() {
        if (latenciesMs.isEmpty()) return 0;
        long sum = 0;
        for (long l : latenciesMs) sum += l;
        return sum / latenciesMs.size();
    }

    public long getPercentile(int percentile) {
        if (latenciesMs.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(latenciesMs);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    public void printSummary() {
        System.out.println("\n--- Results: " + label + " ---");
        System.out.printf("  Sent: %,d, Failed: %,d%n", messagesSent, messagesFailed);
        System.out.printf("  Duration: %.2f s, Throughput: %,.0f msg/s%n", getElapsedSeconds(), getThroughput());
        if (!latenciesMs.isEmpty()) {
            System.out.printf("  Latency avg=%d ms, p50=%d ms, p95=%d ms, p99=%d ms%n",
                    getAvgLatency(), getPercentile(50), getPercentile(95), getPercentile(99));
        }
    }
}
