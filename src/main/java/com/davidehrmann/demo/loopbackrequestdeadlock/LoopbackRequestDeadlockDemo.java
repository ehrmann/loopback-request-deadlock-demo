package com.davidehrmann.demo.loopbackrequestdeadlock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class LoopbackRequestDeadlockDemo {

    private final int processingTime = 150;

    private final ExecutorService externalExecutor;
    private final ExecutorService internalExecutor;

    private final AtomicReference<Counters> counters = new AtomicReference<>(new Counters());

    public LoopbackRequestDeadlockDemo(ExecutorService externalExecutor,
                                       ExecutorService internalExecutor) {
        this.externalExecutor = externalExecutor;
        this.internalExecutor = internalExecutor;
    }

    public void submitTask() {
        try {
            CompletableFuture.runAsync(this::externalTask, externalExecutor)
                    .whenComplete((v, e) -> {
                        if (e != null) {
                            counters.get().errorCount.getAndIncrement();
                        } else {
                            counters.get().successCount.getAndIncrement();
                        }
                    });
        } catch (RejectedExecutionException e) {
            counters.get().errorCount.getAndIncrement();
        }
    }

    public Counters getAndResetCounters() {
        return counters.getAndSet(new Counters());
    }

    private void externalTask() {
        try {
            CompletableFuture.runAsync(this::internalTask, internalExecutor)
                    .get(1, TimeUnit.SECONDS);
            Thread.sleep(processingTime);
        } catch (InterruptedException | ExecutionException | RejectedExecutionException
                | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    private void internalTask() {
        try {
            Thread.sleep(processingTime);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static class Counters {
        private final AtomicLong successCount = new AtomicLong();
        private final AtomicLong errorCount = new AtomicLong();

        public long getSuccessCount() {
            return successCount.get();
        }

        public long getErrorCount() {
            return errorCount.get();
        }
    }

    private static void runUntilStable(LoopbackRequestDeadlockDemo demo, int stabilityThreshold)
            throws InterruptedException {
        int rate = 30;
        int increment = 10;
        long lastRateIncrease = System.currentTimeMillis();

        Deque<Long> history = new ArrayDeque<>();

        double stdDev = 0;
        double mean = 0;

        while (history.size() < stabilityThreshold || stdDev > increment) {
            long start = System.nanoTime();

            demo.submitTask();

            if (System.currentTimeMillis() - lastRateIncrease >= 1000) {
                lastRateIncrease = System.currentTimeMillis();

                Counters counters = demo.getAndResetCounters();

                System.out.printf("rate: %d, success: %d, errors: %d%n",
                        rate, counters.getSuccessCount(), counters.getErrorCount());
                rate += increment;

                history.addLast(counters.getSuccessCount());
                if (history.size() > stabilityThreshold) {
                    history.removeFirst();
                }

                double instanceMean = mean = history.stream()
                        .mapToLong(Long::longValue)
                        .average()
                        .orElse(0);

                double variance = history.stream()
                        .mapToDouble(Long::doubleValue)
                        .map(v -> (instanceMean - v) * (instanceMean - v))
                        .average()
                        .orElse(0);

                stdDev = Math.sqrt(variance);
            }

            long delay = (int) Math.max((start + (1000000000L / rate)) - System.nanoTime(), 0);
            Thread.sleep(delay / 1000000);
        }

        System.out.printf("Throughput stable at %.0f rps; quitting%n", mean);
    }

    public static void main(String[] args) throws Exception {

        // Test mock service making loopback requests using a single thread pool and a large queue.
        // This deadlocks when overloaded because requests are in the queue longer than their
        // timeout, so the internal call never completes before its timeout.
        ExecutorService largeQueueExecutor = new ThreadPoolExecutor(
                64, 64, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1024));
        try {
            System.out.println("Testing with a single thread pool and a large queue");
            runUntilStable(new LoopbackRequestDeadlockDemo(largeQueueExecutor, largeQueueExecutor), 10);
            System.out.println();
        } finally {
            largeQueueExecutor.shutdownNow();
        }

        // Test mock service making loopback requests using a single thread pool and a small queue
        // The small queue causes the loopback requests to fail quickly. The service keeps fulfilling
        // requests, despite being overloaded.
        ExecutorService smallQueueExecutor = new ThreadPoolExecutor(
                64, 64, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
        try {
            System.out.println("Testing with a single thread pool and a small queue");
            runUntilStable(new LoopbackRequestDeadlockDemo(smallQueueExecutor, smallQueueExecutor), 10);
            System.out.println();
        } finally {
            smallQueueExecutor.shutdownNow();
        }

        // Test mock service making loopback requests using two thread pools and large queues
        // The second thread pool and queue can't deadlock and avoid having to pick a safe queue size
        ExecutorService externalExecutor = new ThreadPoolExecutor(
                32, 32, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(512));
        try {
            ExecutorService internalExecutor = new ThreadPoolExecutor(
                    32, 32, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(512));
            try {
                System.out.println("Testing with two thread pools and a large queue");
                runUntilStable(new LoopbackRequestDeadlockDemo(externalExecutor, internalExecutor), 32);
                System.out.println();
            } finally  {
                internalExecutor.shutdownNow();
            }
        } finally {
            externalExecutor.shutdownNow();
        }
    }
}
