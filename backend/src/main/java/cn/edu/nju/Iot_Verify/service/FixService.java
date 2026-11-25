package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.dto.fix.FaultLocalizationResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixApplyResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRange;
import cn.edu.nju.Iot_Verify.dto.model.InteractiveOperationStage;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface FixService {

    /** Verify that an owned trace can be used as current evidence before asking for confirmation. */
    void requireCurrentVerificationSource(Long userId, Long traceId);

    /**
     * Localize fault rules from a counterexample trace (fast, pure in-memory).
     */
    FaultLocalizationResultDto localizeFault(Long userId, Long traceId);

    /**
     * Attempt to fix a violation with optional preferred parameter ranges keyed by ParameterTarget.targetId.
     * (may invoke NuSMV multiple times).
     */
    FixResultDto fix(Long userId, Long traceId, List<String> strategies, Map<String, PreferredRange> preferredRanges);

    FixResultDto fix(Long userId, Long traceId, List<String> strategies,
                     Map<String, PreferredRange> preferredRanges,
                     Consumer<InteractiveOperationStage> progressListener);

    /**
     * Apply a repair strategy to the user's persisted board rules.
     *
     * <p>The caller supplies the exact displayed suggestion and its server-issued signature. The
     * service checks the signature and board/template drift before persisting that same change.</p>
     */
    FixApplyResultDto applyFix(Long userId, Long traceId, String strategy,
                               FixSuggestionDto suggestion, String suggestionToken,
                               Map<String, PreferredRange> preferredRanges);

}
