package cn.edu.nju.Iot_Verify.component.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRequestControlTest {

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void cancellationInterruptsTheWorkerInsideItsReadOnlyScope() throws Exception {
        LlmRequestControl control = new LlmRequestControl();
        try (AutoCloseable scope = control.interruptCurrentThreadOnCancellation()) {
            assertFalse(Thread.currentThread().isInterrupted());
            control.cancel();
            assertTrue(Thread.currentThread().isInterrupted());
        }
    }

    @Test
    void anAlreadyStoppedRequestCannotStartAnUninterruptedReadOnlyWorker() throws Exception {
        LlmRequestControl control = new LlmRequestControl();
        control.cancel();
        try (AutoCloseable scope = control.interruptCurrentThreadOnCancellation()) {
            assertTrue(Thread.currentThread().isInterrupted());
        }
    }

    @Test
    void endingTheScopeDisarmsCancellationBeforeTheWorkerCanBeReused() throws Exception {
        LlmRequestControl control = new LlmRequestControl();
        try (AutoCloseable scope = control.interruptCurrentThreadOnCancellation()) {
            assertFalse(control.isCancellationRequested());
        }
        assertFalse(control.isCancellationRequested());
        control.cancel();
        assertFalse(Thread.currentThread().isInterrupted());
    }
}
