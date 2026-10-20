package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControl;
import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControlHolder;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/** One bounded, cancellable wait contract for the three independent async task runners. */
public final class AiTaskStatusWaiter {

    public static final int MAX_WAIT_MS = 10000;

    private AiTaskStatusWaiter() {
    }

    public static Map<String, Object> waitMsSchema() {
        return Map.of("type", "integer", "minimum", 0, "maximum", MAX_WAIT_MS,
                "description", "Wait up to this many milliseconds for an active task to finish; default "
                        + MAX_WAIT_MS + ". Use 0 for an immediate snapshot.");
    }

    public static <T> T await(Supplier<T> readTask, Function<T, String> status, int waitMs)
            throws InterruptedException {
        T task = readTask.get();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        LlmRequestControl control = LlmRequestControlHolder.currentOrNew();
        while ("PENDING".equals(status.apply(task)) || "RUNNING".equals(status.apply(task))) {
            if (control.isCancellationRequested() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Task status waiting stopped; the task was not cancelled.");
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(250)));
            task = readTask.get();
        }
        return task;
    }
}
