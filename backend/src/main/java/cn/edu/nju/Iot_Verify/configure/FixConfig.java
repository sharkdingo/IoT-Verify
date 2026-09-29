package cn.edu.nju.Iot_Verify.configure;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "fix")
public class FixConfig {

    @Min(1)
    private int maxAttempts = 20;

    @Min(1)
    private int maxCandidatesPerRule = 5;

    /**
     * Max refinement loop iterations for §5.3 closest-value search.
     * Each iteration = 1 NuSMV ¬ρ solve + up to 1 forward-verify (≤ 2 NuSMV calls).
     * The try-original step runs outside this budget (extra cost ≤ param count, typically 1-3).
     * Total NuSMV calls for refinement ≤ paramCount + maxRefineAttempts × 2.
     * Shared across the thresholds of one listed parameter alternative; each alternative the
     * strategy lists is refined with a budget of its own.
     */
    @Min(1)
    private int maxRefineAttempts = 10;

    @Min(1000)
    private long fixTimeoutMs = 300_000;
}
