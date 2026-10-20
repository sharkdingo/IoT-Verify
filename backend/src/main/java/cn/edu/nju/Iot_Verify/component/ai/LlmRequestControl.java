package cn.edu.nju.Iot_Verify.component.ai;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Owns the cancellable work handle for the current assistant request. */
public final class LlmRequestControl {

    private final BooleanSupplier stopSignal;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean alternativeTransportRequested = new AtomicBoolean(false);
    private final AtomicReference<AutoCloseable> activeHandle = new AtomicReference<>();

    public LlmRequestControl() {
        this(() -> false);
    }

    public LlmRequestControl(BooleanSupplier stopSignal) {
        this.stopSignal = Objects.requireNonNull(stopSignal, "stopSignal must not be null");
    }

    public boolean isCancellationRequested() {
        if (cancelled.get()) return true;
        if (stopSignal.getAsBoolean()) {
            cancel();
            return true;
        }
        return false;
    }

    /** A provider may avoid a failed protocol for subsequent calls in this same user turn. */
    public void requestAlternativeTransport() { alternativeTransportRequested.set(true); }

    public boolean isAlternativeTransportRequested() { return alternativeTransportRequested.get(); }

    public void attach(AutoCloseable handle) {
        Objects.requireNonNull(handle, "handle must not be null");
        AutoCloseable previous = activeHandle.getAndSet(handle);
        closeQuietly(previous);
        if (isCancellationRequested() && activeHandle.compareAndSet(handle, null)) {
            closeQuietly(handle);
        }
    }

    public void detach(AutoCloseable handle) {
        activeHandle.compareAndSet(handle, null);
    }

    /** Binds cancellation to a synchronous read-only worker until this scope ends. */
    public AutoCloseable interruptCurrentThreadOnCancellation() {
        ThreadInterruptHandle handle = new ThreadInterruptHandle(Thread.currentThread());
        attach(handle);
        return () -> {
            // A cancelling thread may already hold the handle. Disarming under the same monitor
            // prevents that late callback from interrupting a later job on the reused worker.
            synchronized (handle) {
                handle.armed = false;
                detach(handle);
            }
        };
    }

    private static final class ThreadInterruptHandle implements AutoCloseable {
        private final Thread thread;
        private boolean armed = true;

        private ThreadInterruptHandle(Thread thread) {
            this.thread = thread;
        }

        @Override
        public synchronized void close() {
            if (armed) thread.interrupt();
        }
    }

    public void cancel() {
        cancelled.set(true);
        closeQuietly(activeHandle.getAndSet(null));
    }

    private void closeQuietly(AutoCloseable handle) {
        if (handle == null) return;
        try {
            handle.close();
        } catch (Exception ignored) {
            // Provider shutdown/cancellation is best-effort and must remain idempotent.
        }
    }
}
