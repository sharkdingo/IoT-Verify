package cn.edu.nju.Iot_Verify.configure;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Data
@Configuration
@Validated
@ConfigurationProperties(prefix = "chat.execution")
public class ChatExecutionConfig {

    /** Maximum independently running assistant conversations for one user. */
    @Min(1)
    private int maxConcurrentSessionsPerUser = 4;

    /**
     * Emergency runaway guard, not a normal task budget.
     */
    @Min(8)
    private int maxToolRounds = 64;

    /**
     * Stop only after this many consecutive rounds repeat the exact same calls and results.
     */
    @Min(1)
    private int maxStagnantRounds = 2;

    /** Maximum UTF-8 size of one tool result sent to the model and persisted in chat history. */
    @Min(4096)
    private int maxToolResultBytes = 49_152;

    /** Hard cap for one model planning response so one round cannot create unbounded rows. */
    @Min(1)
    private int maxToolCallsPerRound = 16;

    /** Stored-message cap per session. Users can start another session or delete old history. */
    @Min(100)
    private int maxMessagesPerSession = 5_000;

    /** Shared soft target for task memory and recent coherent history, measured in Java characters. */
    @Min(4000)
    private int historyCharLimit = 32_000;

    /**
     * Distributed lease lifetime. A live worker renews this independently of the SSE connection.
     */
    @Min(3000)
    private long leaseTtlMs = 30_000;

    /**
     * Delay between lease heartbeats and expired-lease cleanup passes.
     */
    @Min(1000)
    private long leaseHeartbeatMs = 10_000;

    @AssertTrue(message = "chat.execution.lease-ttl-ms must be at least twice lease-heartbeat-ms")
    public boolean isLeaseTimingValid() {
        return leaseHeartbeatMs <= leaseTtlMs / 2;
    }

    @AssertTrue(message = "chat.execution.max-messages-per-session must reserve one complete worst-case tool turn")
    public boolean isMessageCapacityValid() {
        try {
            long required = Math.addExact(2L, Math.multiplyExact(
                    1L + maxToolCallsPerRound, (long) maxToolRounds));
            return maxMessagesPerSession >= required;
        } catch (ArithmeticException e) {
            return false;
        }
    }
}
