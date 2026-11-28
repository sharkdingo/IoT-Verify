package cn.edu.nju.Iot_Verify.util.mapper;

import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import cn.edu.nju.Iot_Verify.util.TraceStateIntegrity;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;
import java.util.function.Supplier;

/** Reconstructs the one-based evidence shared by verification and simulation traces. */
final class PersistedTraceEvidence {

    private PersistedTraceEvidence() {
    }

    static List<TraceStateDto> readStates(String recordType, Long id, String statesJson, String emptyDetail) {
        List<TraceStateDto> states = JsonUtils.readPersistedRequired(recordType, id, "statesJson",
                () -> TraceStateIntegrity.requireValidStates(JsonUtils.fromJson(
                        statesJson, new TypeReference<List<TraceStateDto>>() {})));
        if (states.isEmpty()) {
            throw new PersistedDataIntegrityException(recordType, id, "statesJson", emptyDetail);
        }
        return states;
    }

    static void requireStateCount(String recordType, Long id, List<TraceStateDto> states, int expected) {
        if (states.size() != expected) {
            throw new PersistedDataIntegrityException(
                    recordType, id, "stateCount", "state count does not match statesJson");
        }
    }

    static List<RuleDto> readFrozenRules(
            String recordType, Long id, Supplier<List<RuleDto>> readRules, int expectedRuleCount) {
        return JsonUtils.readPersisted(recordType, id, "requestJson",
                () -> TraceStateIntegrity.requireFrozenRules(readRules.get(), expectedRuleCount));
    }

    static List<TraceStateDto> validateRuleEvidence(
            String recordType, Long id, List<TraceStateDto> states, List<RuleDto> frozenRules) {
        return JsonUtils.readPersisted(recordType, id, "statesJson", () -> {
            TraceStateIntegrity.requireRuleEvidenceMatchesFrozenRules(states, frozenRules);
            return states;
        });
    }
}
