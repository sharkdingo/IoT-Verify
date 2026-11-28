package cn.edu.nju.Iot_Verify.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Temporary model artifacts and deterministic executors for verification and simulation tests. */
final class ModelRunFixtures {
    private ModelRunFixtures() {}

    static File createTempModelFile(Path tempDir) throws IOException {
        Path runDir = Files.createTempDirectory(tempDir, "run-");
        return Files.createFile(runDir.resolve("model.smv")).toFile();
    }

    static int readResultCode(File smvFile) throws IOException {
        return readJson(smvFile, "result.json").path("code").asInt();
    }

    static JsonNode readRequestJson(File smvFile) throws IOException {
        return readJson(smvFile, "request.json");
    }

    private static JsonNode readJson(File smvFile, String name) throws IOException {
        File jsonFile = new File(smvFile.getParentFile(), name);
        assertTrue(jsonFile.exists(), () -> "Missing run artifact: " + jsonFile);
        return new ObjectMapper().readTree(jsonFile);
    }

    static BlockedWorker blockWorker(ThreadPoolTaskExecutor executor) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> task = executor.submit(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        BlockedWorker blocker = new BlockedWorker(release, task);
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS), "expected the worker to be occupied");
            return blocker;
        } catch (InterruptedException | AssertionError e) {
            blocker.close();
            throw e;
        }
    }

    static Throwable interruptQueuedRun(ThreadPoolTaskExecutor executor, String name, Runnable run)
            throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                run.run();
            } catch (Throwable error) {
                failure.set(error);
            }
        }, name);
        var queue = executor.getThreadPoolExecutor().getQueue();
        caller.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (queue.size() != 1 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, queue.size());
        } finally {
            caller.interrupt();
            caller.join(2000);
        }
        assertFalse(caller.isAlive());
        return failure.get();
    }

    record BlockedWorker(CountDownLatch release, Future<?> task) implements AutoCloseable {
        @Override
        public void close() {
            release.countDown();
            task.cancel(true);
        }
    }

    static final class DirectThreadPoolTaskExecutor extends ThreadPoolTaskExecutor {
        private Runnable beforeExecution = () -> {};

        void beforeExecution(Runnable action) {
            beforeExecution = action;
        }

        @Override
        public void execute(Runnable task) {
            beforeExecution.run();
            task.run();
        }
    }

    static final class RejectingThreadPoolTaskExecutor extends ThreadPoolTaskExecutor {
        @Override
        public void execute(Runnable task) {
            throw new TaskRejectedException("rejected");
        }
    }

    static final class FailingThreadPoolTaskExecutor extends ThreadPoolTaskExecutor {
        @Override
        public void execute(Runnable task) {
            throw new IllegalStateException("executor failed");
        }
    }

    static final class CapturingThreadPoolTaskExecutor extends ThreadPoolTaskExecutor {
        private Runnable capturedTask;

        @Override
        public void execute(Runnable task) {
            capturedTask = task;
        }

        Runnable capturedTask() {
            return capturedTask;
        }
    }
}
