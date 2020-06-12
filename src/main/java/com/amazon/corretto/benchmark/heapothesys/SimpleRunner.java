package com.amazon.corretto.benchmark.heapothesys;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Simple runner class to evenly divide the load and sent to multiple runners.
 */
public class SimpleRunner extends TaskBase {

    private final SimpleRunConfig config;
    private final static AtomicInteger THREAD_COUNTER = new AtomicInteger(0);

    public SimpleRunner(SimpleRunConfig config) {
        this.config = config;
    }

    @Override
    public void start() {
        try {
            AllocObject.setOverhead(config.isUseCompressedOops() ? AllocObject.ObjectOverhead.CompressedOops
                    : AllocObject.ObjectOverhead.NonCompressedOops);
            final ObjectStore store = new ObjectStore(config.getLongLivedInMb(), config.getPruneRatio(),
                    config.getReshuffleRatio());
            new Thread(store).start();
            final ExecutorService executor = Executors.newFixedThreadPool(config.getNumOfThreads(), runnable -> {
                Thread thread = new Thread(runnable);
                thread.setDaemon(true);
                thread.setName("Heapothesys-" + THREAD_COUNTER.incrementAndGet());
                return thread;
            });
            final List<Future<Long>> results = executor.invokeAll(createTasks(store));

            long sum = 0;
            try {
                for (Future<Long> r : results) {
                    final long t = r.get();
                    sum += t;
                }
            } catch (ExecutionException ex) {
                ex.printStackTrace();
                printResult(-1);
                System.exit(1);
            }

            store.stopAndReturnSize();
            printResult((config.getAllocRateInMbPerSecond() * 1024L * 1024L * config.getDurationInSecond() - sum)
                    / config.getDurationInSecond() / 1024 / 1024);
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }

    private void printResult(final long realAllocRate) throws IOException {
        try (FileWriter fw = new FileWriter(config.getLogFile(), true)) {
            fw.write(config.getHeapSizeInMb() + ","
                    + config.getAllocRateInMbPerSecond() + ","
                    + realAllocRate + ","
                    + ((double) (config.getLongLivedInMb() + config.getMidAgedInMb()) / config.getHeapSizeInMb()) + ","
                    + config.isUseCompressedOops() + ","
                    + config.getNumOfThreads() + ","
                    + config.getMinObjectSize() + ","
                    + config.getMaxObjectSize() + ","
                    + config.getPruneRatio() + ","
                    + config.getReshuffleRatio() + ",\n"
            );
        }
    }

    private List<Callable<Long>> createTasks(final ObjectStore store) {
        final int queueSize = config.getMidAgedInMb() * 1024 * 1024 * 2
                / (config.getMaxObjectSize() + config.getMinObjectSize())
                / config.getNumOfThreads();

        return IntStream.range(0, config.getNumOfThreads())
                .mapToObj(i -> createSingle(store, config.getAllocRateInMbPerSecond() / config.getNumOfThreads(),
                        config.getDurationInSecond() * 1000, config.getMinObjectSize(),
                        config.getMaxObjectSize(), queueSize))
                .collect(Collectors.toList());
    }
}
