package ch.unisg.kafka.experiments.util;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Samples JVM process CPU and heap memory at regular intervals during an experiment run
public class ResourceMonitor {

    private final List<Sample> samples = new ArrayList<>();
    private ScheduledExecutorService scheduler;
    private final OperatingSystemMXBean osMxBean;
    private final MemoryMXBean memoryMxBean;

    public ResourceMonitor() {
        this.osMxBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        this.memoryMxBean = ManagementFactory.getMemoryMXBean();
    }

    public void start(long intervalMs) {
        samples.clear();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "resource-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::takeSample, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void takeSample() {
        double cpuLoad = osMxBean.getProcessCpuLoad();
        MemoryUsage heapUsage = memoryMxBean.getHeapMemoryUsage();
        synchronized (samples) {
            samples.add(new Sample(
                    System.currentTimeMillis(),
                    cpuLoad,
                    heapUsage.getUsed(),
                    heapUsage.getMax()
            ));
        }
    }

    public void printSummary() {
        synchronized (samples) {
            if (samples.isEmpty()) {
                System.out.println("  (no resource samples collected)");
                return;
            }

            double avgCpu = 0, peakCpu = 0;
            long avgHeap = 0, peakHeap = 0;
            long heapMax = samples.get(0).heapMaxBytes;
            int validCpuSamples = 0;

            for (Sample s : samples) {
                // getProcessCpuLoad() returns -1.0 if not yet available
                if (s.cpuLoad >= 0) {
                    avgCpu += s.cpuLoad;
                    peakCpu = Math.max(peakCpu, s.cpuLoad);
                    validCpuSamples++;
                }
                avgHeap += s.heapUsedBytes;
                peakHeap = Math.max(peakHeap, s.heapUsedBytes);
            }

            if (validCpuSamples > 0) {
                avgCpu /= validCpuSamples;
            }
            avgHeap /= samples.size();

            System.out.printf("  CPU:  avg=%.1f%%, peak=%.1f%%%n",
                    avgCpu * 100, peakCpu * 100);
            System.out.printf("  Heap: avg=%d MB, peak=%d MB (max=%d MB)%n",
                    avgHeap / (1024 * 1024),
                    peakHeap / (1024 * 1024),
                    heapMax / (1024 * 1024));
        }
    }

    private static class Sample {
        final long timestamp;
        final double cpuLoad;
        final long heapUsedBytes;
        final long heapMaxBytes;

        Sample(long timestamp, double cpuLoad, long heapUsedBytes, long heapMaxBytes) {
            this.timestamp = timestamp;
            this.cpuLoad = cpuLoad;
            this.heapUsedBytes = heapUsedBytes;
            this.heapMaxBytes = heapMaxBytes;
        }
    }
}
