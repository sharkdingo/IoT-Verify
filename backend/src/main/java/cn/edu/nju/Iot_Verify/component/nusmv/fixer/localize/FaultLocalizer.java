package cn.edu.nju.Iot_Verify.component.nusmv.fixer.localize;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceReferenceResolver;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.fix.FaultRuleDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceTriggeredRuleDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Identifies persisted rule executions and conflicts along a counterexample trace. */
@Component
public class FaultLocalizer {

    public List<FaultRuleDto> localize(List<TraceStateDto> states,
                                       List<RuleDto> rules,
                                       Map<String, DeviceSmvData> deviceSmvMap) {
        if (states == null || states.size() < 2 || rules == null || rules.isEmpty()) {
            return List.of();
        }

        List<FaultRuleDto> faultRules = new ArrayList<>();
        Set<String> seenRuleSteps = new HashSet<>();
        for (int step = 0; step < states.size() - 1; step++) {
            TraceStateDto nextState = states.get(step + 1);
            validateTriggeredRuleIndexes(nextState, rules.size());
            List<FaultRuleDto> stepTriggered = new ArrayList<>();
            for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                RuleDto rule = rules.get(ruleIndex);
                if (rule == null || rule.getCommand() == null
                        || rule.getCommand().getAction() == null) {
                    continue;
                }

                String targetDeviceName = rule.getCommand().getDeviceName();
                String action = rule.getCommand().getAction();
                DeviceSmvData targetSmv = findDevice(targetDeviceName, deviceSmvMap);
                if (targetSmv == null || !isRecordedAsTriggered(nextState, ruleIndex)) {
                    continue;
                }
                DeviceManifest.API matchedApi =
                        DeviceSmvDataFactory.findApi(targetSmv.getManifest(), action);
                if (matchedApi == null || !seenRuleSteps.add(ruleIndex + ":" + step)) {
                    continue;
                }

                stepTriggered.add(FaultRuleDto.builder()
                        .ruleIndex(ruleIndex)
                        .ruleId(rule.getId())
                        .ruleString(rule.getRuleString())
                        .transitionNumber(step + 1)
                        .targetDeviceId(targetDeviceName)
                        .targetDeviceLabel(displayDeviceLabel(targetSmv, targetDeviceName))
                        .targetActionId(action)
                        .targetActionLabel(displayActionLabel(matchedApi, action))
                        // The same authored spelling is used in playback and in both conflict labels.
                        .targetEndState(describeEndState(targetSmv, matchedApi.getEndState()))
                        .reasonCode("TRIGGERED")
                        .build());
            }
            detectConflicts(stepTriggered, rules, deviceSmvMap);
            faultRules.addAll(stepTriggered);
        }
        return faultRules;
    }

    private void validateTriggeredRuleIndexes(TraceStateDto state, int ruleCount) {
        if (state == null || state.getTriggeredRules() == null) {
            return;
        }
        Set<Integer> seenIndexes = new HashSet<>();
        for (TraceTriggeredRuleDto triggered : state.getTriggeredRules()) {
            Integer ruleIndex = triggered == null ? null : triggered.getRuleIndex();
            if (ruleIndex == null || ruleIndex < 0 || ruleIndex >= ruleCount) {
                throw new IllegalArgumentException(
                        "Trace triggered rule index is outside the frozen rule list");
            }
            if (!seenIndexes.add(ruleIndex)) {
                throw new IllegalArgumentException(
                        "Trace contains duplicate triggered rule indexes");
            }
        }
    }

    private boolean isRecordedAsTriggered(TraceStateDto state, int ruleIndex) {
        if (state == null || state.getTriggeredRules() == null) {
            return false;
        }
        return state.getTriggeredRules().stream()
                .anyMatch(triggered -> triggered != null
                        && triggered.getRuleIndex() != null
                        && triggered.getRuleIndex() == ruleIndex);
    }

    private void detectConflicts(List<FaultRuleDto> stepTriggered,
                                 List<RuleDto> rules,
                                 Map<String, DeviceSmvData> deviceSmvMap) {
        for (int left = 0; left < stepTriggered.size(); left++) {
            FaultRuleDto first = stepTriggered.get(left);
            for (int right = left + 1; right < stepTriggered.size(); right++) {
                FaultRuleDto second = stepTriggered.get(right);
                if (!first.getTargetDeviceId().equals(second.getTargetDeviceId())) {
                    continue;
                }

                RuleDto firstRule = rules.get(first.getRuleIndex());
                RuleDto secondRule = rules.get(second.getRuleIndex());
                DeviceSmvData smv = findDevice(first.getTargetDeviceId(), deviceSmvMap);
                if (smv == null) {
                    continue;
                }
                DeviceManifest.API firstApi = DeviceSmvDataFactory.findApi(
                        smv.getManifest(), firstRule.getCommand().getAction());
                DeviceManifest.API secondApi = DeviceSmvDataFactory.findApi(
                        smv.getManifest(), secondRule.getCommand().getAction());
                if (firstApi == null || secondApi == null) {
                    continue;
                }

                String firstEndState = firstApi.getEndState();
                String secondEndState = secondApi.getEndState();
                if (!hasConflictingModeTargets(smv, firstEndState, secondEndState)) {
                    continue;
                }

                String secondDescription = describeRule(rules, second.getRuleIndex());
                String firstDescription = describeRule(rules, first.getRuleIndex());
                String firstLabel = describeEndState(smv, firstEndState);
                String secondLabel = describeEndState(smv, secondEndState);
                first.setConflicting(true);
                first.setConflictWithRuleIndex(second.getRuleIndex());
                first.setConflictingRuleString(rulePreview(rules, second.getRuleIndex()));
                first.setConflictingEndState(secondLabel);
                first.setReasonCode("CONFLICTING_END_STATES");
                first.setReason("Conflicts with " + secondDescription
                        + ": both change " + first.getTargetDeviceLabel()
                        + " to different states (" + firstLabel + " and "
                        + secondLabel + ").");

                second.setConflicting(true);
                second.setConflictWithRuleIndex(first.getRuleIndex());
                second.setConflictingRuleString(rulePreview(rules, first.getRuleIndex()));
                second.setConflictingEndState(firstLabel);
                second.setReasonCode("CONFLICTING_END_STATES");
                second.setReason("Conflicts with " + firstDescription
                        + ": both change " + second.getTargetDeviceLabel()
                        + " to different states (" + secondLabel + " and "
                        + firstLabel + ").");
            }
        }

        for (FaultRuleDto fault : stepTriggered) {
            if (fault.getReason() == null) {
                fault.setReason("Rule fired during transition " + fault.getTransitionNumber()
                        + ": " + fault.getTargetActionLabel() + " on "
                        + fault.getTargetDeviceLabel() + ".");
            }
        }
    }

    private boolean hasConflictingModeTargets(
            DeviceSmvData smv, String firstEndState, String secondEndState) {
        if (smv.getModes() == null || firstEndState == null || secondEndState == null) {
            return false;
        }
        String[] firstTargets = firstEndState.split(";", -1);
        String[] secondTargets = secondEndState.split(";", -1);
        for (int modeIndex = 0; modeIndex < smv.getModes().size(); modeIndex++) {
            String firstTarget = targetForMode(firstTargets, modeIndex);
            String secondTarget = targetForMode(secondTargets, modeIndex);
            if (firstTarget != null && secondTarget != null
                    && !firstTarget.equals(secondTarget)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Render authored state names in mode order, omitting slots for modes the command leaves unchanged.
     *
     * <p>The separator must stay one of {@code ; , |}: for a bundled template the frontend's
     * {@code formatBuiltInModelToken} splits on those and localizes each token, so any other joiner
     * (e.g. {@code " / "}) makes the whole string miss the catalogue and render raw in a non-English UI.
     */
    private String describeEndState(DeviceSmvData smv, String endState) {
        if (endState == null || endState.isBlank()) {
            return endState;
        }
        List<String> modes = smv.getModes();
        if (modes == null || modes.isEmpty()) {
            return endState.trim();
        }
        String[] targets = endState.split(";", -1);
        List<String> labels = new ArrayList<>();
        for (int modeIndex = 0; modeIndex < modes.size(); modeIndex++) {
            String target = targetForMode(targets, modeIndex);
            if (target != null) {
                labels.add(smv.authoredStateName(modes.get(modeIndex), target));
            }
        }
        return String.join("; ", labels);
    }

    private String targetForMode(String[] targets, int modeIndex) {
        if (modeIndex >= targets.length || targets[modeIndex].isBlank()) {
            return null;
        }
        return DeviceSmvDataFactory.cleanStateName(targets[modeIndex]);
    }

    /**
     * A rule's own preview text, or null when it has none.
     *
     * Split from {@link #describeRule} because one value cannot serve both consumers. {@code describeRule} builds
     * English prose for {@code reason} — a diagnostic, where English is fine — and quotes the text as
     * {@code 'like this'}. {@code conflictingRuleString} is different: the client interpolates it into an
     * already-translated sentence, so the quotes came out doubled (与"'When motion…'"冲突) and the English
     * "another localized rule" fallback appeared inside Chinese copy. Sending the raw value or null lets the
     * client quote and localise it, the same division of labour as {@code ruleString}.
     */
    private String rulePreview(List<RuleDto> rules, int ruleIndex) {
        if (ruleIndex >= 0 && ruleIndex < rules.size()) {
            RuleDto rule = rules.get(ruleIndex);
            if (rule != null && rule.getRuleString() != null && !rule.getRuleString().isBlank()) {
                return rule.getRuleString();
            }
        }
        return null;
    }

    /** English prose for the {@code reason} diagnostic; quotes the preview and names an unlabelled rule. */
    private String describeRule(List<RuleDto> rules, int ruleIndex) {
        if (ruleIndex >= 0 && ruleIndex < rules.size()) {
            RuleDto rule = rules.get(ruleIndex);
            if (rule != null && rule.getRuleString() != null && !rule.getRuleString().isBlank()) {
                return "'" + rule.getRuleString() + "'";
            }
        }
        return "another localized rule";
    }

    private String displayDeviceLabel(DeviceSmvData smv, String fallback) {
        if (smv.getDeviceLabel() != null && !smv.getDeviceLabel().isBlank()) {
            return smv.getDeviceLabel();
        }
        return fallback;
    }

    private String displayActionLabel(DeviceManifest.API api, String fallback) {
        if (api.getDescription() != null && !api.getDescription().isBlank()) {
            return api.getDescription().trim();
        }
        return fallback;
    }

    private DeviceSmvData findDevice(String deviceName,
                                     Map<String, DeviceSmvData> deviceSmvMap) {
        if (deviceName == null || deviceSmvMap == null) {
            return null;
        }
        return DeviceReferenceResolver.resolve(deviceName, deviceSmvMap);
    }

}
