package cn.edu.nju.Iot_Verify.component.nusmv.fixer.localize;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.fix.FaultRuleDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceTriggeredRuleDto;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaultLocalizerTest {

    private final FaultLocalizer localizer = new FaultLocalizer();

    @Test
    void localizesOnlyTheRuleRecordedByTheExecutionProbe() {
        RuleDto executed = rule(1L, "Executed rule", "light_1", "turn_on");
        RuleDto suppressed = rule(2L, "Suppressed rule", "light_1", "turn_on");
        DeviceSmvData light = device("light_1", "Hall light", List.of(
                api("turn_on", "Turn on", "on")));
        List<TraceStateDto> states = List.of(
                state(1),
                state(2, trigger(0, "1", "Executed rule")));

        List<FaultRuleDto> faults = localizer.localize(
                states, List.of(executed, suppressed), Map.of("light_1", light));

        assertEquals(1, faults.size());
        FaultRuleDto fault = faults.get(0);
        assertEquals(1L, fault.getRuleId());
        assertEquals(0, fault.getRuleIndex());
        assertEquals(1, fault.getTransitionNumber());
        assertEquals("Hall light", fault.getTargetDeviceLabel());
        assertEquals("Turn on", fault.getTargetActionLabel());
        assertEquals("TRIGGERED", fault.getReasonCode());
        assertFalse(fault.isConflicting());
    }

    /*
     * A rule with no ruleString passes its null through, rather than the backend inventing a label.
     *
     * RuleDto.ruleString has no @NotBlank and a nullable TEXT column, so a rule persists without one - verified
     * against the running API, which accepts a rule with the field omitted and echoes "ruleString": null. The
     * frontend used to reject the whole fault-localization response for that (text(row, 'ruleString') throws on
     * null), so a fix request failed as "malformed result".
     *
     * The fix went in the UI, not here: it now renders the localised `Rule {number}`, matching what three other
     * components already do for TraceRule.ruleLabel. A server-side English label would have shown a zh-CN user
     * English text, which the bilingual rule in CLAUDE.md forbids.
     */
    @Test
    void passesThroughANullRuleStringInsteadOfInventingALabel() {
        RuleDto unlabelled = RuleDto.builder()
                .id(7L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("light_1")
                        .attribute("state")
                        .targetType("state")
                        .relation("=")
                        .value("on")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("light_1")
                        .action("turn_on")
                        .build())
                .build();
        DeviceSmvData light = device("light_1", "Hall light", List.of(
                api("turn_on", "Turn on", "on")));

        var faults = localizer.localize(
                List.of(state(1), state(2, trigger(0, "7", null))),
                List.of(unlabelled),
                Map.of("light_1", light));

        assertEquals(1, faults.size());
        assertNull(faults.get(0).getRuleString(),
                "the backend must not invent a label the UI would have to un-localise");
    }

    /*
     * The conflict path sends the raw preview, or null, keeping the English prose only in `reason`.
     *
     * This is the case that actually exercises `rulePreview`. The single-rule test above cannot: with one rule
     * there is no conflict, so it stayed green even with the production change reverted — a guard that named the
     * code it could not reach. Here an unlabelled partner rule makes `conflictingRuleString` null while `reason`
     * keeps the English "another localized rule", which is the whole point of the split.
     */
    @Test
    void sendsARawConflictingPreviewAndLeavesTheEnglishFallbackToTheReason() {
        RuleDto labelled = rule(1L, "Turn light on", "light_1", "turn_on");
        RuleDto unlabelled = RuleDto.builder()
                .id(2L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("light_1").attribute("state").targetType("state")
                        .relation("=").value("on").build()))
                .command(RuleDto.Command.builder().deviceName("light_1").action("turn_off").build())
                .build();
        DeviceSmvData light = device("light_1", "Hall light", List.of(
                api("turn_on", "Turn on", "on"),
                api("turn_off", "Turn off", "off")));

        List<FaultRuleDto> faults = localizer.localize(
                List.of(state(1), state(2, trigger(0, "1", "Turn light on"), trigger(1, "2", null))),
                List.of(labelled, unlabelled),
                Map.of("light_1", light));

        assertEquals(2, faults.size());
        // The labelled rule's partner has no preview, so its conflicting label is null rather than English prose.
        assertNull(faults.get(0).getConflictingRuleString());
        assertTrue(faults.get(0).getReason().contains("another localized rule"),
                "the English fallback belongs in the diagnostic: " + faults.get(0).getReason());
        // And the reverse direction still carries the partner's real preview, unquoted.
        assertEquals("Turn light on", faults.get(1).getConflictingRuleString());
    }

    @Test
    void doesNotReconstructExecutionFromConditionsOrStateChanges() {
        RuleDto rule = rule(1L, "Would match old heuristic", "light_1", "turn_on");
        DeviceSmvData light = device("light_1", "Hall light", List.of(
                api("turn_on", "Turn on", "on")));

        assertTrue(localizer.localize(
                List.of(state(1), state(2)),
                List.of(rule),
                Map.of("light_1", light)).isEmpty());
    }

    @Test
    void usesTheFrozenRuleIndexWhenNoRuleIdExists() {
        RuleDto rule = rule(null, "  Label-only rule  ", "light_1", "turn_on");
        DeviceSmvData light = device("light_1", "Hall light", List.of(
                api("turn_on", "Turn on", "on")));

        List<FaultRuleDto> faults = localizer.localize(
                List.of(state(1), state(2, trigger(0, null, "Label-only rule"))),
                List.of(rule),
                Map.of("light_1", light));

        assertEquals(1, faults.size());
        assertEquals("  Label-only rule  ", faults.get(0).getRuleString());
    }

    @Test
    void marksRecordedRulesWithDifferentEndStatesAsConflicting() {
        RuleDto turnOn = rule(1L, "Turn light on", "light_1", "turn_on");
        RuleDto turnOff = rule(2L, "Turn light off", "light_1", "turn_off");
        DeviceSmvData light = device("light_1", "Hall light", List.of(
                api("turn_on", "Turn on", "on"),
                api("turn_off", "Turn off", "off")));

        List<FaultRuleDto> faults = localizer.localize(
                List.of(state(1), state(2,
                        trigger(0, "1", "Turn light on"),
                        trigger(1, "2", "Turn light off"))),
                List.of(turnOn, turnOff),
                Map.of("light_1", light));

        assertEquals(2, faults.size());
        assertTrue(faults.get(0).isConflicting());
        assertTrue(faults.get(1).isConflicting());
        assertEquals("CONFLICTING_END_STATES", faults.get(0).getReasonCode());
        // Raw preview, unquoted: the client interpolates this into an already-translated sentence, so quoting it
        // here produced 与“'Turn light off'”冲突. The English prose in `reason` keeps its quotes.
        assertEquals("Turn light off", faults.get(0).getConflictingRuleString());
        assertTrue(faults.get(0).getReason().contains("'Turn light off'"));
        assertEquals(1, faults.get(0).getConflictWithRuleIndex());
        assertEquals(0, faults.get(1).getConflictWithRuleIndex());
    }

    @Test
    void describesAMultiModeConflictInStateNamesRatherThanRawManifestTuples() {
        RuleDto cool = rule(1L, "Start cooling", "ac_1", "cool");
        RuleDto stop = rule(2L, "Stop the AC", "ac_1", "stop");
        DeviceSmvData ac = multiModeDevice("ac_1", "Living-room AC", List.of(
                api("cool", "Cool", "Case;night standby"),
                api("stop", "Stop", "ready;night standby")));
        ac.setAuthoredStatesByMode(Map.of(
                "Mode", Map.of("_Case", "Case", "ready", "ready"),
                "Fan", Map.of("nightstandby", "night standby")));

        List<FaultRuleDto> faults = localizer.localize(
                List.of(state(1), state(2,
                        trigger(0, "1", "Start cooling"),
                        trigger(1, "2", "Stop the AC"))),
                List.of(cool, stop),
                Map.of("ac_1", ac));

        assertEquals(2, faults.size());
        assertTrue(faults.get(0).isConflicting());
        assertEquals("Case; night standby", faults.get(0).getTargetEndState());
        assertEquals("ready; night standby", faults.get(0).getConflictingEndState());
        assertTrue(faults.get(0).getReason().contains("Case; night standby"));
    }

    @Test
    void recordsTheSameRuleOncePerTransition() {
        RuleDto rule = rule(1L, "Repeated rule", "light_1", "turn_on");
        DeviceSmvData light = device("light_1", "Hall light", List.of(
                api("turn_on", "Turn on", "on")));
        TraceTriggeredRuleDto trigger = trigger(0, "1", "Repeated rule");

        List<FaultRuleDto> faults = localizer.localize(
                List.of(state(1), state(2, trigger), state(3, trigger)),
                List.of(rule),
                Map.of("light_1", light));

        assertEquals(2, faults.size());
        assertEquals(List.of(1, 2), faults.stream()
                .map(FaultRuleDto::getTransitionNumber)
                .toList());
    }

    @Test
    void ignoresProbeEntriesThatCannotResolveToACurrentRuleAndApi() {
        RuleDto unknownDevice = rule(1L, "Unknown device", "missing", "turn_on");
        RuleDto unknownApi = rule(2L, "Unknown API", "light_1", "missing_action");
        RuleDto malformed = RuleDto.builder().id(3L).ruleString("Malformed").build();
        DeviceSmvData light = device("light_1", "Hall light", List.of(
                api("turn_on", "Turn on", "on")));

        List<FaultRuleDto> faults = localizer.localize(
                List.of(state(1), state(2,
                        trigger(0, "1", "Unknown device"),
                        trigger(1, "2", "Unknown API"),
                        trigger(2, "3", "Malformed"))),
                List.of(unknownDevice, unknownApi, malformed),
                Map.of("light_1", light));

        assertTrue(faults.isEmpty());
    }

    @Test
    void handlesInsufficientInputsWithoutInventingEvidence() {
        assertTrue(localizer.localize(null, List.of(), Map.of()).isEmpty());
        assertTrue(localizer.localize(List.of(state(1)), List.of(), Map.of()).isEmpty());
        assertTrue(localizer.localize(List.of(state(1), state(2)), null, Map.of()).isEmpty());
    }

    @Test
    void doesNotMislabelDeviceMapFailuresAsAnUnknownDevice() {
        RuleDto rule = rule(1L, "Recorded rule", "light_1", "turn_on");
        Map<String, DeviceSmvData> brokenMap = new AbstractMap<>() {
            @Override
            public Set<Entry<String, DeviceSmvData>> entrySet() {
                return Set.of(Map.entry("light_1", new DeviceSmvData()));
            }

            @Override
            public DeviceSmvData get(Object key) {
                throw new IllegalStateException("device map unavailable");
            }
        };

        assertThrows(IllegalStateException.class, () -> localizer.localize(
                List.of(state(1), state(2, trigger(0, "1", "Recorded rule"))),
                List.of(rule), brokenMap));
    }

    @Test
    void duplicateRuleIdsCannotMakeAnUnrecordedRuleLookExecuted() {
        RuleDto first = rule(7L, "First duplicate", "light_1", "turn_on");
        RuleDto second = rule(7L, "Second duplicate", "light_1", "turn_off");
        DeviceSmvData light = device("light_1", "Hall light", List.of(
                api("turn_on", "Turn on", "on"),
                api("turn_off", "Turn off", "off")));

        List<FaultRuleDto> faults = localizer.localize(
                List.of(state(1), state(2, trigger(1, "7", "Second duplicate"))),
                List.of(first, second),
                Map.of("light_1", light));

        assertEquals(1, faults.size());
        assertEquals(1, faults.get(0).getRuleIndex());
        assertEquals("Second duplicate", faults.get(0).getRuleString());
    }

    @Test
    void equalLabelsWithoutIdsCannotMakeAnUnrecordedRuleLookExecuted() {
        RuleDto first = rule(null, "Repeated label", "light_1", "turn_on");
        RuleDto second = rule(null, "Repeated label", "light_1", "turn_off");
        DeviceSmvData light = device("light_1", "Hall light", List.of(
                api("turn_on", "Turn on", "on"),
                api("turn_off", "Turn off", "off")));

        List<FaultRuleDto> faults = localizer.localize(
                List.of(state(1), state(2, trigger(0, null, "Repeated label"))),
                List.of(first, second),
                Map.of("light_1", light));

        assertEquals(1, faults.size());
        assertEquals(0, faults.get(0).getRuleIndex());
    }

    @Test
    void disjointMultiModeWritesAreNotReportedAsConflicting() {
        RuleDto power = rule(1L, "Power on", "climate_1", "power_on");
        RuleDto fan = rule(2L, "Raise fan", "climate_1", "fan_high");
        DeviceSmvData climate = device("climate_1", "Climate controller", List.of(
                api("power_on", "Power on", "on;"),
                api("fan_high", "Raise fan", ";high")));
        climate.getModes().clear();
        climate.getModes().addAll(List.of("PowerMode", "FanMode"));

        List<FaultRuleDto> faults = localizer.localize(
                List.of(state(1), state(2,
                        trigger(0, "1", "Power on"),
                        trigger(1, "2", "Raise fan"))),
                List.of(power, fan),
                Map.of("climate_1", climate));

        assertEquals(2, faults.size());
        assertTrue(faults.stream().noneMatch(FaultRuleDto::isConflicting));
        assertTrue(faults.stream().allMatch(fault -> "TRIGGERED".equals(fault.getReasonCode())));
    }

    @Test
    void rejectsTriggeredRuleIndexesOutsideTheFrozenRuleList() {
        RuleDto rule = rule(1L, "Only rule", "light_1", "turn_on");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> localizer.localize(
                        List.of(state(1), state(2, trigger(1, "1", "Only rule"))),
                        List.of(rule), Map.of()));

        assertEquals("Trace triggered rule index is outside the frozen rule list",
                error.getMessage());
    }

    @Test
    void rejectsDuplicateTriggeredRuleIndexes() {
        RuleDto rule = rule(1L, "Only rule", "light_1", "turn_on");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> localizer.localize(
                        List.of(state(1), state(2,
                                trigger(0, "1", "Only rule"),
                                trigger(0, "1", "Only rule"))),
                        List.of(rule), Map.of()));

        assertEquals("Trace contains duplicate triggered rule indexes", error.getMessage());
    }

    private RuleDto rule(Long id, String label, String deviceId, String action) {
        return RuleDto.builder()
                .id(id)
                .ruleString(label)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName(deviceId)
                        .attribute("state")
                        .relation("=")
                        .value("on")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName(deviceId)
                        .action(action)
                        .build())
                .build();
    }

    private DeviceSmvData device(String id,
                                 String label,
                                 List<DeviceManifest.API> apis) {
        DeviceSmvData device = new DeviceSmvData();
        device.setVarName(id);
        device.setDeviceLabel(label);
        device.setTemplateName("Light");
        device.getModes().add("Mode");
        device.setManifest(DeviceManifest.builder()
                .name("Light")
                .apis(apis)
                .build());
        return device;
    }

    /** Two modes, so an API's end state is the `;`-joined internal tuple a real manifest stores. */
    private DeviceSmvData multiModeDevice(String id, String label, List<DeviceManifest.API> apis) {
        DeviceSmvData device = device(id, label, apis);
        device.getModes().add("Fan");
        return device;
    }

    private DeviceManifest.API api(String name, String description, String endState) {
        return DeviceManifest.API.builder()
                .name(name)
                .description(description)
                .endState(endState)
                .build();
    }

    private TraceStateDto state(int index, TraceTriggeredRuleDto... triggeredRules) {
        return TraceStateDto.builder()
                .stateIndex(index)
                .devices(List.of())
                .triggeredRules(List.of(triggeredRules))
                .compromisedAutomationLinks(List.of())
                .build();
    }

    private TraceTriggeredRuleDto trigger(int ruleIndex, String id, String label) {
        return TraceTriggeredRuleDto.builder()
                .ruleIndex(ruleIndex)
                .ruleId(id)
                .ruleLabel(label)
                .build();
    }
}
