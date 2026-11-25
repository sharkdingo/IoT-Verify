package cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDeviceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceTriggeredRuleDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceTrustPrivacyDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceVariableDto;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CounterexampleInitialStateConstraintsTest {

    @Test
    void buildPinsDeviceEnvironmentAndAttackBranch() {
        DeviceManifest.InternalVariable local = DeviceManifest.InternalVariable.builder()
                .name("level").isInside(true).falsifiableWhenCompromised(true)
                .lowerBound(0).upperBound(10).build();
        DeviceManifest.InternalVariable environment = DeviceManifest.InternalVariable.builder()
                .name("temperature").isInside(false).lowerBound(-10).upperBound(50).build();
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("sensor_1");
        smv.setModes(List.of("Power"));
        smv.getModeStates().put("Power", List.of("on", "off"));
        smv.setVariables(List.of(local, environment));
        smv.getEnvVariables().put("temperature", environment);
        smv.getSharedDeclarations().put("temperature", environment);

        TraceVariableDto localTrace = variable("level", "7");
        localTrace.setTrust("trusted");
        TraceVariableDto environmentDeviceTrace = variable("temperature", null);
        environmentDeviceTrace.setTrust("trusted");
        TraceDeviceDto device = new TraceDeviceDto();
        device.setDeviceId("sensor_1");
        device.setState("on");
        device.setCompromised(true);
        device.setVariables(List.of(localTrace, environmentDeviceTrace));
        device.setTrustPrivacy(List.of(
                stateTrust("Power", "on", true),
                stateTrust("Power", "off", true)));

        TraceStateDto state = TraceStateDto.builder()
                .stateIndex(1)
                .devices(List.of(device))
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of(
                        TraceTriggeredRuleDto.builder().ruleIndex(1)
                                .ruleId("22").ruleLabel("second").build()))
                .envVariables(List.of(variable("temperature", "18")))
                .build();
        List<RuleDto> rules = List.of(
                RuleDto.builder().id(11L).ruleString("first").build(),
                RuleDto.builder().id(22L).ruleString("second").build());

        List<String> constraints = CounterexampleInitialStateConstraints.build(
                state, rules, new LinkedHashMap<>(Map.of("sensor_1", smv)),
                AttackScenarioDto.anyUpToBudget(2), false);

        assertTrue(constraints.contains("sensor_1.Power = on"));
        assertTrue(constraints.contains("sensor_1.level = 7"));
        assertTrue(constraints.contains("a_temperature = 18"));
        assertTrue(constraints.contains("sensor_1.is_attack = TRUE"));
        assertTrue(constraints.contains("iot_verify_automation_link_compromised_0 = FALSE"));
        assertTrue(constraints.contains("iot_verify_automation_link_compromised_1 = TRUE"));
        assertEquals(10, constraints.size());
    }

    @Test
    void buildRejectsIncompleteFirstStateInsteadOfLeavingValuesFree() {
        DeviceManifest.InternalVariable local = DeviceManifest.InternalVariable.builder()
                .name("level").isInside(true).lowerBound(0).upperBound(10).build();
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("sensor_1");
        smv.setModes(List.of("Power"));
        smv.getModeStates().put("Power", List.of("on", "off"));
        smv.setVariables(List.of(local));

        TraceDeviceDto incomplete = new TraceDeviceDto();
        incomplete.setDeviceId("sensor_1");
        incomplete.setState("on");
        incomplete.setVariables(List.of(variable("level", "7")));
        TraceStateDto state = TraceStateDto.builder()
                .stateIndex(1)
                .devices(List.of(incomplete))
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .build();

        assertThrows(SmvGenerationException.class, () ->
                CounterexampleInitialStateConstraints.build(
                        state, List.of(), Map.of("sensor_1", smv), AttackScenarioDto.none(), false));
    }

    private TraceVariableDto variable(String name, String value) {
        TraceVariableDto variable = new TraceVariableDto();
        variable.setName(name);
        variable.setValue(value);
        return variable;
    }

    private TraceTrustPrivacyDto stateTrust(String mode, String state, boolean trusted) {
        TraceTrustPrivacyDto property = new TraceTrustPrivacyDto();
        property.setPropertyScope("state");
        property.setMode(mode);
        property.setName(state);
        property.setTrust(trusted);
        return property;
    }
}
