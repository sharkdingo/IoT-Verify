package cn.edu.nju.Iot_Verify.testutil;

import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.DistributedInteractiveExecutionStore;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

public final class InteractiveExecutionFixtures {
    private InteractiveExecutionFixtures() {}

    public static DistributedInteractiveExecutionStore unavailableRedisStore() {
        return new DistributedInteractiveExecutionStore(mock(StringRedisTemplate.class, invocation -> {
            throw new RedisConnectionFailureException("Redis unavailable");
        }));
    }

    public static ThreadPoolTaskExecutor singleWorkerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        return executor;
    }

    public static CompletableFuture<Void> runCancellableRequest(Runnable execute) {
        return CompletableFuture.runAsync(() -> {
            try {
                execute.run();
            } catch (ServiceUnavailableException expected) {
                // Cancellation releases the waiting HTTP request.
            }
        });
    }

    public static InterruptibleRequest startInterruptibleRequest(Consumer<Callable<Void>> execute) {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CompletableFuture<Void> response = runCancellableRequest(() -> execute.accept(() -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        return new InterruptibleRequest(response, started, interrupted);
    }

    public record InterruptibleRequest(
            CompletableFuture<Void> response, CountDownLatch started, CountDownLatch interrupted) {
        public void awaitStarted() throws InterruptedException {
            assertTrue(started.await(2, TimeUnit.SECONDS));
        }

        public void awaitResponseAndInterrupt() throws Exception {
            response.get(2, TimeUnit.SECONDS);
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        }
    }

    // The caller releases the sole occupied worker after exercising queued cancellation.
    public static CountDownLatch occupyExecutorWorker(ThreadPoolTaskExecutor executor) throws InterruptedException {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        executor.execute(() -> {
            workerStarted.countDown();
            try {
                releaseWorker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
        return releaseWorker;
    }

    public static void awaitQueuedTask(ThreadPoolTaskExecutor executor, String taskDescription) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (executor.getThreadPoolExecutor().getQueue().isEmpty()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for queued " + taskDescription);
            }
            Thread.yield();
        }
    }
}
