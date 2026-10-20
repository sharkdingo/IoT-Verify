package cn.edu.nju.Iot_Verify.component.nusmv.parser;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDeviceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceTrustPrivacyDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceVariableDto;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SmvTraceParserTest {

    private final SmvTraceParser parser = new SmvTraceParser();

    @Test
    void parseCounterexample_mapsInternalRuleProbesToStableTriggeredRuleSnapshots() {
        RuleDto first = RuleDto.builder().id(41L).ruleString("Turn on the hallway light").build();
        RuleDto second = RuleDto.builder().id(42L).ruleString("Notify the resident").build();
        String counterexample = """
                -> State: 1.1 <-
                  iot_verify_rule_fired_0 = FALSE
                  iot_verify_rule_fired_1 = FALSE
                  iot_verify_automation_link_compromised_0 = TRUE
                  iot_verify_automation_link_compromised_1 = FALSE
                -> State: 1.2 <-
                  iot_verify_rule_fired_0 = TRUE
                -> State: 1.3 <-
                  iot_verify_rule_fired_0 = FALSE
                  iot_verify_rule_fired_1 = TRUE
                -> State: 1.4 <-
                """;

        List<TraceStateDto> states = parser.parseCounterexampleStates(
                counterexample, Map.of(), List.of(first, second));

        assertTrue(states.get(0).getTriggeredRules().isEmpty());
        assertEquals(0, states.get(1).getTriggeredRules().get(0).getRuleIndex());
        assertEquals("41", states.get(1).getTriggeredRules().get(0).getRuleId());
        assertEquals("Turn on the hallway light", states.get(1).getTriggeredRules().get(0).getRuleLabel());
        assertEquals("42", states.get(2).getTriggeredRules().get(0).getRuleId());
        assertEquals(1, states.get(2).getTriggeredRules().get(0).getRuleIndex());
        assertEquals("42", states.get(3).getTriggeredRules().get(0).getRuleId(),
                "NuSMV omits unchanged values, so probe state must carry forward");
        assertEquals("41", states.get(0).getCompromisedAutomationLinks().get(0).getRuleId());
        assertEquals(0, states.get(0).getCompromisedAutomationLinks().get(0).getRuleIndex());
        assertEquals("Turn on the hallway light",
                states.get(3).getCompromisedAutomationLinks().get(0).getRuleLabel(),
                "Frozen automation-link choices must carry forward without exposing generated indexes");
        assertNull(findGlobalValue(states.get(1), "iot_verify_rule_fired_0"),
                "Internal probes must not leak into runtime globals");
        assertNull(findGlobalValue(states.get(1), "iot_verify_automation_link_compromised_0"),
                "Internal automation-link attack choices must not leak into runtime globals");
    }

    @Test
    void parseCounterexample_materializesCompleteSnapshotsFromNuSmvDeltas() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("sensor_1");
        smv.setDeviceLabel("Hall sensor");
        smv.setTemplateName("Sensor");
        smv.setModelTokenSource(ModelTokenSource.BUNDLED);
        smv.getModes().add("Mode");
        smv.getModeStates().put("Mode", List.of("idle", "active"));
        smv.getStates().addAll(List.of("idle", "active"));
        smv.getVariables().add(internalVariable("temperature"));
        smv.getEnvVariables().put("temperature", internalVariable("temperature"));
        smv.getContents().add(content("photo"));

        String counterexample = """
                -> State: 1.1 <-
                  sensor_1.Mode = idle
                  sensor_1.temperature = 20
                  sensor_1.trust_temperature = untrusted
                  sensor_1.privacy_photo = private
                  sensor_1.is_attack = TRUE
                  a_temperature = 20
                  iot_verify_compromised_point_count = 1
                -> State: 1.2 <-
                  sensor_1.temperature = 21
                  a_temperature = 21
                -> State: 1.3 <-
                """;

        List<TraceStateDto> states = parser.parseCounterexampleStates(
                counterexample, Map.of("sensor_1", smv));

        TraceDeviceDto secondDevice = states.get(1).getDevices().get(0);
        assertEquals(ModelTokenSource.BUNDLED, secondDevice.getModelTokenSource());
        assertEquals("idle", secondDevice.getState());
        assertEquals("21", findVariable(secondDevice, "temperature").getValue());
        assertEquals(ModelTokenSource.BUNDLED,
                findVariable(secondDevice, "temperature").getModelTokenSource());
        assertEquals("untrusted", findVariable(secondDevice, "temperature").getTrust());
        assertTrue(Boolean.TRUE.equals(secondDevice.getCompromised()));
        assertNull(findVariable(secondDevice, "is_attack"));
        assertEquals("private", findPrivacy(secondDevice, "photo").getPrivacy());
        assertEquals("1", findGlobalValue(states.get(1), "compromisedPointCount"));

        TraceDeviceDto thirdDevice = states.get(2).getDevices().get(0);
        assertEquals("21", findVariable(thirdDevice, "temperature").getValue());
        assertEquals("21", findEnvValue(states.get(2), "temperature"));
        assertEquals(ModelTokenSource.BUNDLED,
                findEnvironmentVariable(states.get(2), "temperature").getModelTokenSource());
        assertEquals(ModelTokenSource.UNKNOWN,
                findGlobalVariable(states.get(2), "compromisedPointCount").getModelTokenSource());
        assertEquals("1", findGlobalValue(states.get(2), "compromisedPointCount"));
        assertNotSame(secondDevice, thirdDevice, "Each API state must be an independent snapshot");
    }

    /**
     * A liveness counterexample is an infinite lasso path, and its loop is the violation itself.
     *
     * Verbatim shape of NuSMV 2.7.1 output for `AG((occupancy=absent & LockState=unlocked) -> AF(LockState=locked))`
     * on a real board (trace 83): the marker sits on its own line before the cycle's first state, and the
     * final state closes the loop carrying no variable lines at all. Without capturing the marker, that
     * final state materializes identical to its predecessor and plays back as a step where nothing happens —
     * which is how a "the door never re-locks" violation reached the user as a frozen animation.
     */
    @Test
    void parseCounterexample_marksLassoLoopStartAndTheStateThatClosesIt() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("door_1");
        smv.setDeviceLabel("Front Door");
        smv.setTemplateName("Door");
        smv.setModelTokenSource(ModelTokenSource.BUNDLED);
        smv.getModes().add("LockState");
        smv.getModeStates().put("LockState", List.of("locked", "unlocked"));
        smv.getStates().addAll(List.of("locked", "unlocked"));

        String counterexample = """
                -> State: 2.1 <-
                  door_1.LockState = locked
                -> State: 2.2 <-
                  door_1.LockState = unlocked
                -- Loop starts here
                -> State: 2.3 <-
                  door_1.unlock_a = FALSE
                -> State: 2.4 <-
                """;

        List<TraceStateDto> states = parser.parseCounterexampleStates(
                counterexample, Map.of("door_1", smv));

        assertEquals(4, states.size());
        assertNull(states.get(0).getLoopStart(), "a state before the marker starts no loop");
        assertNull(states.get(1).getLoopStart());
        assertTrue(Boolean.TRUE.equals(states.get(2).getLoopStart()),
                "the state after the marker begins the cycle");
        assertNull(states.get(2).getLoopBack(), "the loop entry is not itself the return");
        assertTrue(Boolean.TRUE.equals(states.get(3).getLoopBack()),
                "the trailing repeat is what makes the path infinite");
        // The marker is consumed as metadata, never as trace content: it opens no state of its own (4, not 5)
        // and contributes no variable to the state that follows it.
        assertNull(states.get(2).getEnvVariables(), "the marker line yields no environment variable");
        assertNull(states.get(2).getGlobalVariables(), "nor a global one");
        assertEquals("unlocked", states.get(3).getDevices().get(0).getState(),
                "the closing state still materializes its inherited values, which is why it looks static");
    }

    /**
     * A cycle longer than one state closes with real variable lines, and must still be marked.
     *
     * Every other loop test here uses an empty closing state, which is only what a **one-state** cycle looks
     * like. Measured on NuSMV 2.7.1 with the generator's own template-5 shape,
     * {@code AG((motion=yes) -> AF(light=on))} over a model that alternates motion and never lights: the
     * 3-state counterexample closes with {@code motion = yes} printed, so the final state differs from its
     * predecessor and equals the loop *entry*.
     *
     * That is the case a value comparison gets backwards. A consumer checking "did anything change?" to detect
     * the repetition sees an ordinary step here and no repetition at all, which is why {@code loopBack} is
     * carried rather than derived — and why the docs must not describe the empty-repeat case as the general
     * one.
     */
    @Test
    void parseCounterexample_marksAMultiStateCycleWhoseClosingStateCarriesChanges() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("sensor_1");
        smv.setDeviceLabel("Hall Sensor");
        smv.setTemplateName("MotionSensor");
        smv.getModes().add("MotionState");
        smv.getModeStates().put("MotionState", List.of("yes", "no"));
        smv.getStates().addAll(List.of("yes", "no"));

        String counterexample = """
                -- Loop starts here
                -> State: 1.1 <-
                  sensor_1.MotionState = yes
                -> State: 1.2 <-
                  sensor_1.MotionState = no
                -> State: 1.3 <-
                  sensor_1.MotionState = yes
                """;

        List<TraceStateDto> states = parser.parseCounterexampleStates(
                counterexample, Map.of("sensor_1", smv));

        assertEquals(3, states.size());
        assertTrue(Boolean.TRUE.equals(states.get(0).getLoopStart()),
                "the marker precedes the first state, so the whole trace is the cycle");
        assertTrue(Boolean.TRUE.equals(states.get(2).getLoopBack()),
                "the closing state is marked even though it carries its own values");
        // The point of the case: it differs from its predecessor and matches the entry.
        assertEquals("no", states.get(1).getDevices().get(0).getState());
        assertEquals("yes", states.get(2).getDevices().get(0).getState(),
                "the closing state repeats the loop entry, not the state before it");
    }

    /**
     * The cycle can begin at the initial state, putting the marker before the first state line.
     *
     * NuSMV 2.7.1 prints exactly this for `AF x` over a model that never sets `x`. The whole trace is then one
     * cycle, so the first state carries `loopStart` and the trailing repeat still closes it.
     */
    @Test
    void parseCounterexample_marksALoopThatStartsAtTheInitialState() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("light_1");
        smv.setDeviceLabel("Porch Light");
        smv.setTemplateName("Light");
        smv.getModes().add("SwitchState");
        smv.getModeStates().put("SwitchState", List.of("on", "off"));
        smv.getStates().addAll(List.of("on", "off"));

        String counterexample = """
                -- Loop starts here
                -> State: 1.1 <-
                  light_1.SwitchState = off
                -> State: 1.2 <-
                """;

        List<TraceStateDto> states = parser.parseCounterexampleStates(
                counterexample, Map.of("light_1", smv));

        assertEquals(2, states.size());
        assertTrue(Boolean.TRUE.equals(states.get(0).getLoopStart()),
                "the initial state can itself begin the cycle");
        assertTrue(Boolean.TRUE.equals(states.get(1).getLoopBack()));
        assertEquals(1, states.get(0).getStateIndex(), "the marker must not shift the state numbering");
    }

    /**
     * NuSMV can print the marker twice in one trace; the last occurrence is the one that counts.
     *
     * Reproduced on NuSMV 2.7.1 with `LTLSPEC G(x < 2)`. The rule has to be stated somewhere, and it is stated
     * here: `loopBack` is computed against the last marker, and the frontend reads the last one too, so the
     * two agree about where the cycle begins instead of over-marking by a state.
     */
    @Test
    void parseCounterexample_usesTheLastMarkerWhenNuSmvPrintsSeveral() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("light_1");
        smv.setDeviceLabel("Porch Light");
        smv.setTemplateName("Light");
        smv.getModes().add("SwitchState");
        smv.getModeStates().put("SwitchState", List.of("on", "off"));
        smv.getStates().addAll(List.of("on", "off"));

        String counterexample = """
                -> State: 1.1 <-
                  light_1.SwitchState = off
                -> State: 1.2 <-
                  light_1.SwitchState = on
                -- Loop starts here
                -> State: 1.3 <-
                -- Loop starts here
                -> State: 1.4 <-
                -> State: 1.5 <-
                """;

        List<TraceStateDto> states = parser.parseCounterexampleStates(
                counterexample, Map.of("light_1", smv));

        assertEquals(5, states.size());
        assertTrue(Boolean.TRUE.equals(states.get(2).getLoopStart()));
        assertTrue(Boolean.TRUE.equals(states.get(3).getLoopStart()));
        assertTrue(Boolean.TRUE.equals(states.get(4).getLoopBack()),
                "the closing state is the last one regardless of how many markers appeared");
        assertNull(states.get(3).getLoopBack());
    }

    /** A finite safety counterexample has no cycle, so neither flag may be invented. */
    @Test
    void parseCounterexample_leavesLoopFlagsAbsentForAFinitePath() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("door_1");
        smv.setDeviceLabel("Front Door");
        smv.setTemplateName("Door");
        smv.getModes().add("LockState");
        smv.getModeStates().put("LockState", List.of("locked", "unlocked"));
        smv.getStates().addAll(List.of("locked", "unlocked"));

        String counterexample = """
                -> State: 1.1 <-
                  door_1.LockState = locked
                -> State: 1.2 <-
                  door_1.LockState = unlocked
                """;

        List<TraceStateDto> states = parser.parseCounterexampleStates(
                counterexample, Map.of("door_1", smv));

        assertEquals(2, states.size());
        states.forEach(state -> {
            assertNull(state.getLoopStart());
            assertNull(state.getLoopBack());
        });
    }

    @Test
    void parseCounterexample_parsesModeTrustPrivacyEnvAndMapsCompromiseState() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("air_conditioner_1");
        smv.setDeviceLabel("Bedroom AC");
        smv.setTemplateName("Air Conditioner");
        smv.getModes().add("HvacMode");
        smv.getModeStates().put("HvacMode", List.of("cool", "heat"));
        smv.getStates().addAll(List.of("cool", "heat"));
        smv.getEnvVariables().put("temperature", null);
        smv.getContents().add(content("photo"));

        Map<String, DeviceSmvData> deviceMap = new LinkedHashMap<>();
        deviceMap.put("air_conditioner_1", smv);

        String counterexample = """
                -> State: 1.1 <-
                  air_conditioner_1.HvacMode = cool
                  air_conditioner_1.trust_HvacMode_cool = trusted
                  air_conditioner_1.trust_temperature = untrusted
                  air_conditioner_1.temperature = 25
                  air_conditioner_1.temperature_rate = -1
                  air_conditioner_1.is_attack = FALSE
                  air_conditioner_1.cool_a = TRUE
                  air_conditioner_1.privacy_photo = private
                  a_temperature = 25
                  iot_verify_compromised_point_count = 1
                -> State: 1.2 <-
                  air_conditioner_1.HvacMode = heat
                  a_temperature = 24
                """;

        List<TraceStateDto> states = parser.parseCounterexampleStates(counterexample, deviceMap);

        assertEquals(2, states.size());

        TraceStateDto s1 = states.get(0);
        TraceDeviceDto d1 = s1.getDevices().get(0);
        assertEquals("cool", d1.getState());
        assertEquals("HvacMode", d1.getMode());
        assertEquals("Air Conditioner", d1.getTemplateName());
        assertEquals("Bedroom AC", d1.getDeviceLabel());

        TraceVariableDto temp = findVariable(d1, "temperature");
        assertNotNull(temp);
        assertEquals("25", temp.getValue());
        assertEquals("untrusted", temp.getTrust());
        assertNull(findVariable(d1, "temperature_rate"));
        assertFalse(Boolean.TRUE.equals(d1.getCompromised()));
        assertNull(findVariable(d1, "is_attack"));
        assertNull(findVariable(d1, "cool_a"));

        TraceTrustPrivacyDto trust = findTrust(d1, "cool");
        assertNotNull(trust);
        assertTrue(Boolean.TRUE.equals(trust.getTrust()));
        assertEquals("state", trust.getPropertyScope());
        assertEquals("HvacMode", trust.getMode());

        TraceTrustPrivacyDto privacy = findPrivacy(d1, "photo");
        assertNotNull(privacy);
        assertEquals("private", privacy.getPrivacy());
        assertEquals("content", privacy.getPropertyScope());
        assertNull(privacy.getMode());

        assertEquals("25", findEnvValue(s1, "temperature"));
        assertEquals("1", findGlobalValue(s1, "compromisedPointCount"));
        assertEquals("24", findEnvValue(states.get(1), "temperature"));
        assertEquals("heat", states.get(1).getDevices().get(0).getState());
    }

    @Test
    void parseCounterexample_stripsOnlyGeneratedEnvironmentPrefix() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("sensor_1");
        smv.setTemplateName("Sensor");
        smv.getEnvVariables().put("temperature", null);
        smv.getEnvVariables().put("a_noise", null);

        Map<String, DeviceSmvData> deviceMap = new LinkedHashMap<>();
        deviceMap.put("sensor_1", smv);

        String counterexample = """
                -> State: 1.1 <-
                  a_temperature = 25
                  a_a_noise = high
                  a_unknown = raw
                """;

        List<TraceStateDto> states = parser.parseCounterexampleStates(counterexample, deviceMap);

        assertEquals(1, states.size());
        assertEquals("25", findEnvValue(states.get(0), "temperature"));
        assertEquals("high", findEnvValue(states.get(0), "a_noise"));
        assertNull(findEnvValue(states.get(0), "a_unknown"));
        assertEquals("raw", findGlobalValue(states.get(0), "a_unknown"),
                "Unknown bare SMV names are runtime globals, not user environment variables");
    }

    @Test
    void parseCounterexample_usesUnknownForEnvironmentTokensWithMixedTemplateSources() {
        DeviceSmvData bundled = new DeviceSmvData();
        bundled.setModelTokenSource(ModelTokenSource.BUNDLED);
        bundled.getEnvVariables().put("temperature", internalVariable("temperature"));
        DeviceSmvData custom = new DeviceSmvData();
        custom.setModelTokenSource(ModelTokenSource.CUSTOM);
        custom.getImpactedVariables().add("temperature");

        List<TraceStateDto> states = parser.parseCounterexampleStates("""
                -> State: 1.1 <-
                  a_temperature = 25
                """, Map.of("bundled", bundled, "custom", custom));

        assertEquals(ModelTokenSource.UNKNOWN,
                findEnvironmentVariable(states.get(0), "temperature").getModelTokenSource());
    }

    @Test
    void parseCounterexample_separatesInternalAttackCountFromEnvironmentVariableNamedAttackBudget() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("sensor_1");
        smv.setTemplateName("Sensor");
        smv.getEnvVariables().put("attackBudget", internalVariable("attackBudget"));

        Map<String, DeviceSmvData> deviceMap = new LinkedHashMap<>();
        deviceMap.put("sensor_1", smv);

        String counterexample = """
                -> State: 1.1 <-
                  iot_verify_compromised_point_count = 3
                  a_attackBudget = 42
                """;

        List<TraceStateDto> states = parser.parseCounterexampleStates(counterexample, deviceMap);

        assertEquals(1, states.size());
        assertEquals("42", findEnvValue(states.get(0), "attackBudget"));
        assertEquals("3", findGlobalValue(states.get(0), "compromisedPointCount"));
    }

    @Test
    void parseCounterexample_keepsUserVariablesThatLookLikeGeneratedNames() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("meter_1");
        smv.setTemplateName("Meter");
        smv.getVariables().addAll(List.of(
                internalVariable("trust_score"),
                internalVariable("privacy_level"),
                internalVariable("flow_rate"),
                internalVariable("tap_a"),
                internalVariable("state")
        ));

        Map<String, DeviceSmvData> deviceMap = new LinkedHashMap<>();
        deviceMap.put("meter_1", smv);

        String counterexample = """
                -> State: 1.1 <-
                  meter_1.trust_score = 7
                  meter_1.privacy_level = private
                  meter_1.flow_rate = 5
                  meter_1.tap_a = TRUE
                  meter_1.state = calibrating
                  meter_1.generated_rate = 1
                  meter_1.generated_a = TRUE
                  meter_1.trust_missing = untrusted
                  meter_1.privacy_missing = private
                """;

        List<TraceStateDto> states = parser.parseCounterexampleStates(counterexample, deviceMap);
        TraceDeviceDto device = states.get(0).getDevices().get(0);

        assertEquals("7", findVariable(device, "trust_score").getValue());
        assertEquals("private", findVariable(device, "privacy_level").getValue());
        assertEquals("5", findVariable(device, "flow_rate").getValue());
        assertEquals("TRUE", findVariable(device, "tap_a").getValue());
        assertEquals("calibrating", findVariable(device, "state").getValue());
        assertNull(device.getState(), "Only declared mode variables determine device working state");
        assertNull(findVariable(device, "generated_rate"));
        assertNull(findVariable(device, "generated_a"));
        assertNull(findTrust(device, "score"));
        assertNull(findPrivacy(device, "level"));
        assertNull(findVariable(device, "missing"));
        assertNull(findTrust(device, "missing"));
        assertNull(findPrivacy(device, "missing"));
    }

    @Test
    void parseCounterexample_combinesMultiModeStatesByModeOrder() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("thermostat_1");
        smv.setTemplateName("Thermostat");
        smv.getModes().addAll(List.of("Mode", "ThermostatFanMode"));

        Map<String, DeviceSmvData> deviceMap = new LinkedHashMap<>();
        deviceMap.put("thermostat_1", smv);

        String counterexample = """
                -> State: 1.1 <-
                  thermostat_1.ThermostatFanMode = on
                  thermostat_1.Mode = auto
                """;

        List<TraceStateDto> states = parser.parseCounterexampleStates(counterexample, deviceMap);
        assertEquals(1, states.size());
        assertEquals("auto;on", states.get(0).getDevices().get(0).getState());
        assertEquals("Mode;ThermostatFanMode", states.get(0).getDevices().get(0).getMode());
        assertNotNull(states.get(0).getDevices().get(0).getVariables());
        assertTrue(states.get(0).getDevices().get(0).getVariables().isEmpty(),
                "State-only devices must serialize an explicit empty variables array");
    }

    @Test
    void parseCounterexample_keepsSameNamedStatesDistinctByModeWithoutGeneratedNames() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("device_1");
        smv.setTemplateName("Composite");
        smv.getModes().addAll(List.of("PowerMode", "PrivacyMode"));
        smv.getModeStates().put("PowerMode", List.of("on", "off"));
        smv.getModeStates().put("PrivacyMode", List.of("on", "off"));

        String counterexample = """
                -> State: 1.1 <-
                  device_1.PowerMode = on
                  device_1.PrivacyMode = on
                  device_1.trust_PowerMode_on = trusted
                  device_1.trust_PrivacyMode_on = untrusted
                -> State: 1.2 <-
                """;

        List<TraceStateDto> states = parser.parseCounterexampleStates(
                counterexample, Map.of("device_1", smv));
        List<TraceTrustPrivacyDto> labels = states.get(1).getDevices().get(0).getTrustPrivacy();

        assertEquals(2, labels.size());
        assertTrue(labels.stream().allMatch(label -> "on".equals(label.getName())));
        assertTrue(labels.stream().anyMatch(label -> "PowerMode".equals(label.getMode())
                && Boolean.TRUE.equals(label.getTrust())));
        assertTrue(labels.stream().anyMatch(label -> "PrivacyMode".equals(label.getMode())
                && Boolean.FALSE.equals(label.getTrust())));
    }

    @Test
    void parseCounterexample_backfillsMissingModeFromPreviousState() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("thermostat_1");
        smv.setTemplateName("Thermostat");
        smv.getModes().addAll(List.of("Mode", "ThermostatFanMode"));

        Map<String, DeviceSmvData> deviceMap = new LinkedHashMap<>();
        deviceMap.put("thermostat_1", smv);

        String counterexample = """
                -> State: 1.1 <-
                  thermostat_1.Mode = auto
                  thermostat_1.ThermostatFanMode = on
                -> State: 1.2 <-
                  thermostat_1.Mode = heat
                """;

        List<TraceStateDto> states = parser.parseCounterexampleStates(counterexample, deviceMap);
        assertEquals(2, states.size());
        assertEquals("auto;on", states.get(0).getDevices().get(0).getState());
        assertEquals("heat;on", states.get(1).getDevices().get(0).getState());
    }

    @Test
    void parseCounterexample_handlesTraceNumberGreaterThanOne() {
        // NuSMV may output "State 2.1" for the second trace; parser should handle \d+\.\d+
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("air_conditioner_1");
        smv.setTemplateName("Air Conditioner");
        smv.getModes().add("HvacMode");
        smv.getModeStates().put("HvacMode", List.of("cool", "heat"));
        smv.getStates().addAll(List.of("cool", "heat"));

        Map<String, DeviceSmvData> deviceMap = new LinkedHashMap<>();
        deviceMap.put("air_conditioner_1", smv);

        String counterexample = """
                -> State: 2.1 <-
                  air_conditioner_1.HvacMode = cool
                  air_conditioner_1.temperature = 30
                -> State: 2.2 <-
                  air_conditioner_1.HvacMode = heat
                  air_conditioner_1.temperature = 28
                """;

        List<TraceStateDto> states = parser.parseCounterexampleStates(counterexample, deviceMap);
        assertEquals(2, states.size());
        assertEquals(1, states.get(0).getStateIndex());
        assertEquals(2, states.get(1).getStateIndex());
        assertEquals("cool", states.get(0).getDevices().get(0).getState());
        assertEquals("heat", states.get(1).getDevices().get(0).getState());
    }

    @Test
    void parseCounterexample_doesNotMatchStateMidLine() {
        // Ensure anchored regex doesn't match "State" appearing mid-line
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("dev1");
        smv.setTemplateName("Device");

        Map<String, DeviceSmvData> deviceMap = new LinkedHashMap<>();
        deviceMap.put("dev1", smv);

        String counterexample = """
                -> State: 1.1 <-
                  dev1.status = on
                some text mentioning State: 1.99 in the middle
                -> State: 1.2 <-
                  dev1.status = off
                """;

        List<TraceStateDto> states = parser.parseCounterexampleStates(counterexample, deviceMap);
        // Should only parse 2 states (1.1 and 1.2), not the mid-line "State: 1.99"
        assertEquals(2, states.size());
        assertEquals(1, states.get(0).getStateIndex());
        assertEquals(2, states.get(1).getStateIndex());
    }

    @Test
    void parseCounterexample_keepsEncounterOrderAndDoesNotReuseDevicesAcrossDeltaStates() {
        Map<String, DeviceSmvData> deviceMap = new LinkedHashMap<>();
        for (String id : List.of("first", "second")) {
            DeviceSmvData device = new DeviceSmvData();
            device.setVarName(id);
            device.setTemplateName("Sensor");
            device.setModelTokenSource(ModelTokenSource.BUNDLED);
            device.getVariables().add(internalVariable("value"));
            deviceMap.put(id, device);
        }
        String output = """
                State 4.1:
                  second.value = 1
                  first.value = 2
                  second.value = 3
                  decimal = 1.5
                -> State: 4.2 <-
                  first.value = 4
                  second.value = 5
                State: 4.3
                """;

        List<TraceStateDto> states = parser.parseCounterexampleStates(output, deviceMap);

        assertEquals(3, states.size());
        for (TraceStateDto state : states) {
            assertEquals(List.of("second", "first"),
                    state.getDevices().stream().map(TraceDeviceDto::getDeviceId).toList());
            assertEquals(1, state.getDevices().get(0).getVariables().size());
            assertEquals("1.5", findGlobalValue(state, "decimal"));
        }
        assertEquals("3", findVariable(states.get(0).getDevices().get(0), "value").getValue());
        assertEquals("5", findVariable(states.get(1).getDevices().get(0), "value").getValue());
        assertEquals("4", findVariable(states.get(1).getDevices().get(1), "value").getValue());
        assertNotSame(states.get(0).getDevices().get(0), states.get(1).getDevices().get(0));
        assertNotSame(states.get(1).getDevices().get(0), states.get(2).getDevices().get(0));
    }

    private TraceVariableDto findVariable(TraceDeviceDto dev, String name) {
        if (dev.getVariables() == null) {
            return null;
        }
        return dev.getVariables().stream().filter(v -> name.equals(v.getName())).findFirst().orElse(null);
    }

    private TraceTrustPrivacyDto findTrust(TraceDeviceDto dev, String name) {
        if (dev.getTrustPrivacy() == null) {
            return null;
        }
        return dev.getTrustPrivacy().stream().filter(v -> name.equals(v.getName())).findFirst().orElse(null);
    }

    private TraceTrustPrivacyDto findPrivacy(TraceDeviceDto dev, String name) {
        if (dev.getPrivacies() == null) {
            return null;
        }
        return dev.getPrivacies().stream().filter(v -> name.equals(v.getName())).findFirst().orElse(null);
    }

    private String findEnvValue(TraceStateDto state, String name) {
        if (state.getEnvVariables() == null) {
            return null;
        }
        return state.getEnvVariables().stream()
                .filter(v -> name.equals(v.getName()))
                .map(TraceVariableDto::getValue)
                .findFirst()
                .orElse(null);
    }

    private TraceVariableDto findEnvironmentVariable(TraceStateDto state, String name) {
        if (state.getEnvVariables() == null) return null;
        return state.getEnvVariables().stream()
                .filter(v -> name.equals(v.getName()))
                .findFirst()
                .orElse(null);
    }

    private TraceVariableDto findGlobalVariable(TraceStateDto state, String name) {
        if (state.getGlobalVariables() == null) return null;
        return state.getGlobalVariables().stream()
                .filter(v -> name.equals(v.getName()))
                .findFirst()
                .orElse(null);
    }

    private String findGlobalValue(TraceStateDto state, String name) {
        if (state.getGlobalVariables() == null) {
            return null;
        }
        return state.getGlobalVariables().stream()
                .filter(v -> name.equals(v.getName()))
                .map(TraceVariableDto::getValue)
                .findFirst()
                .orElse(null);
    }

    private DeviceManifest.InternalVariable internalVariable(String name) {
        DeviceManifest.InternalVariable variable = new DeviceManifest.InternalVariable();
        variable.setName(name);
        return variable;
    }

    private DeviceSmvData.ContentInfo content(String name) {
        DeviceSmvData.ContentInfo content = new DeviceSmvData.ContentInfo();
        content.setName(name);
        return content;
    }
}
