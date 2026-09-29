package cn.edu.nju.Iot_Verify.dto.fix;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** User-facing execution status for one requested automatic-fix strategy. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FixStrategyAttemptDto {
    private String strategy;
    /**
     * VERIFIED, NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE, ALL_CANDIDATES_REJECTED, INCONCLUSIVE,
     * FAILED_MODEL_GENERATION, FAILED_SOLVER_EXECUTION, SEARCH_BUDGET_EXHAUSTED, TIMED_OUT,
     * SKIPPED_TIMEOUT, SKIPPED_NO_SPEC, SKIPPED_NO_PARAMETERIZABLE_VALUES, SKIPPED_NO_FAULT_RULES,
     * SKIPPED_UNSUPPORTED, or SKIPPED_INCOMPLETE_SOURCE_MODEL. The first two after VERIFIED are
     * proofs over the searched space; see docs/api/verification.md.
     */
    private String status;
    private String reason;
    /**
     * Set only when VERIFIED: {@code true} when the strategy's search space was exhausted, so its listed
     * suggestions are every minimal repair of that kind; {@code false} when the listing limit, the
     * attempt budget, the time share or an unchecked candidate cut the search short.
     */
    private Boolean alternativesComplete;
}
