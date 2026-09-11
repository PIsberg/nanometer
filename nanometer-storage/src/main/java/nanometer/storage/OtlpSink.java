package nanometer.storage;

import nanometer.export.OtlpJsonExporter;
import nanometer.model.RelationalMetricEvent;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Ships drained events to an OTLP collector.
 *
 * <p>Export runs on its own single thread behind a bounded queue rather than on the flusher's
 * thread. A collector that is slow or unreachable would otherwise delay database writes and back the
 * ring buffer up, so an observability tool would start shedding the application's telemetry because
 * something downstream was unhealthy. When the queue is full, batches are dropped and counted;
 * losing telemetry is the correct trade against stalling the host.
 */
@AIPublicAPI(reason = "Continuous OTLP export of collected spans")
@AIObservability(metrics = {"otlp_batches_exported_total", "otlp_batches_dropped_total"})
public final class OtlpSink implements AutoCloseable {

    private final String endpointUrl;
    private final String serviceName;
    private final ArrayBlockingQueue<List<RelationalMetricEvent>> queue;
    private final ExecutorService worker;
    private final LongAdder exported = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder failed = new LongAdder();

    private volatile boolean running = true;

    public OtlpSink(String endpointUrl, String serviceName) {
        this(endpointUrl, serviceName, 32);
    }

    public OtlpSink(String endpointUrl, String serviceName, int queueCapacity) {
        this.endpointUrl = endpointUrl;
        this.serviceName = serviceName;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nanometer-otlp-export");
            t.setDaemon(true);
            return t;
        });
        this.worker.submit(this::drainForever);
    }

    /** Hands a batch to the export thread, dropping it rather than blocking the caller. */
    public void accept(List<RelationalMetricEvent> batch) {
        if (batch.isEmpty()) {
            return;
        }
        if (!queue.offer(List.copyOf(batch))) {
            dropped.increment();
        }
    }

    private void drainForever() {
        List<List<RelationalMetricEvent>> pending = new ArrayList<>();
        while (running) {
            try {
                List<RelationalMetricEvent> head = queue.poll(500, TimeUnit.MILLISECONDS);
                if (head == null) {
                    continue;
                }
                pending.clear();
                pending.add(head);
                queue.drainTo(pending);

                List<RelationalMetricEvent> combined = new ArrayList<>();
                for (List<RelationalMetricEvent> chunk : pending) {
                    combined.addAll(chunk);
                }
                if (OtlpJsonExporter.sendToOtlpEndpoint(endpointUrl, serviceName, combined)) {
                    exported.increment();
                } else {
                    failed.increment();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // An export must never kill the thread; the next batch should still get a chance.
                failed.increment();
            }
        }
    }

    public long getExportedBatchCount() {
        return exported.sum();
    }

    /** Batches discarded because the collector could not keep up. */
    public long getDroppedBatchCount() {
        return dropped.sum();
    }

    public long getFailedBatchCount() {
        return failed.sum();
    }

    @Override
    public void close() {
        running = false;
        worker.shutdownNow();
    }
}
