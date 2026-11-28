package cn.edu.nju.Iot_Verify.service.impl;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

/** Queue operations used by synchronous model runs. */
final class SynchronousRunExecutorSupport {

    private SynchronousRunExecutorSupport() {
    }

    static String snapshot(ThreadPoolTaskExecutor executor) {
        try {
            ThreadPoolExecutor nativeExecutor = executor.getThreadPoolExecutor();
            return "poolSize=" + nativeExecutor.getPoolSize()
                    + ", active=" + nativeExecutor.getActiveCount()
                    + ", queueSize=" + nativeExecutor.getQueue().size()
                    + ", remainingCapacity=" + nativeExecutor.getQueue().remainingCapacity();
        } catch (IllegalStateException ignored) {
            return "executor=uninitialized";
        }
    }

    static void cancelAndPurge(Future<?> future, ThreadPoolTaskExecutor executor) {
        future.cancel(true);
        try {
            executor.getThreadPoolExecutor().purge();
        } catch (IllegalStateException ignored) {
            // executor may not be initialized yet
        }
    }
}
