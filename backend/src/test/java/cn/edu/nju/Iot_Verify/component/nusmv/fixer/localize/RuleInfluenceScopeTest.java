package cn.edu.nju.Iot_Verify.component.nusmv.fixer.localize;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RuleInfluenceScopeTest {

    /**
     * The shape of the camera-at-night counterexample that timed out: the property names the camera and
     * the bed sensor; a lamp rule turns the camera on and another lamp rule drives that lamp; the CO2 and
     * heart-rate rules fired too but write nothing the property can observe.
     */
    @Test
    void keepsOnlyRulesWhoseEffectsReachThePropertyDevices() {
        Map<String, DeviceSmvData> devices = devices("camera", "bed", "lamp", "window", "co2", "watch", "alarm");
        List<RuleDto> rules = List.of(
                rule("watch", "alarm"),   // 0: heart rate -> alarm
                rule("co2", "window"),    // 1: CO2 -> window
                rule("lamp", "camera"),   // 2: lamp on -> camera
                rule("bed", "lamp"),      // 3: bed occupied -> lamp
                rule("window", "alarm")); // 4: window -> alarm

        Optional<Set<Integer>> scope = RuleInfluenceScope.relevantRuleIndices(
                spec("camera", "bed"), rules, devices);

        assertEquals(Optional.of(Set.of(2, 3)), scope);
    }

    @Test
    void followsSharedEnvironmentDomainsThroughDeclaredImpact() {
        Map<String, DeviceSmvData> devices = devices("thermometer", "heater", "fan", "door");
        DeviceManifest.InternalVariable temperature = DeviceManifest.InternalVariable.builder()
                .name("temperature").isInside(false).lowerBound(0).upperBound(50).build();
        devices.get("thermometer").getEnvVariables().put("temperature", temperature);
        devices.get("heater").getImpactedEnvironmentVariables().put("temperature", temperature);
        List<RuleDto> rules = List.of(
                rule("fan", "heater"),  // 0: writes a device that impacts the property's domain
                rule("fan", "door"));   // 1: unrelated

        Optional<Set<Integer>> scope = RuleInfluenceScope.relevantRuleIndices(
                spec("thermometer"), rules, devices);

        assertEquals(Optional.of(Set.of(0)), scope);
    }

    @Test
    void contentProvenanceOfARelevantRuleIsRelevant() {
        Map<String, DeviceSmvData> devices = devices("display", "camera", "button");
        RuleDto showPhoto = RuleDto.builder()
                .conditions(List.of())
                .command(RuleDto.Command.builder().deviceName("display").action("show")
                        .contentDevice("camera").content("photo").build())
                .build();
        List<RuleDto> rules = List.of(showPhoto, rule("button", "camera"));

        Optional<Set<Integer>> scope = RuleInfluenceScope.relevantRuleIndices(
                spec("display"), rules, devices);

        assertEquals(Optional.of(Set.of(0, 1)), scope,
                "a rule that turns on the content source changes what the display can leak");
    }

    @Test
    void unresolvedPropertyGivesUnknownScopeRatherThanNoRelevantRule() {
        Map<String, DeviceSmvData> devices = devices("lamp");
        SpecConditionDto byLabelOnly = condition("node-1");
        byLabelOnly.setDeviceLabel("lamp");
        SpecificationDto spec = new SpecificationDto();
        spec.setAConditions(List.of(byLabelOnly));

        assertEquals(Optional.empty(), RuleInfluenceScope.relevantRuleIndices(
                spec, List.of(rule("lamp", "lamp")), devices),
                "deviceLabel is display-only and must not resolve the property's device");
        assertEquals(Optional.empty(), RuleInfluenceScope.relevantRuleIndices(
                null, List.of(rule("lamp", "lamp")), devices));
    }

    private static Map<String, DeviceSmvData> devices(String... names) {
        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        for (String name : names) {
            DeviceSmvData smv = new DeviceSmvData();
            smv.setVarName(name);
            map.put(name, smv);
        }
        return map;
    }

    private static RuleDto rule(String conditionDevice, String commandDevice) {
        return RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName(conditionDevice).targetType("state").attribute("state")
                        .relation("=").value("on").build()))
                .command(RuleDto.Command.builder().deviceName(commandDevice).action("on").build())
                .build();
    }

    private static SpecificationDto spec(String... deviceIds) {
        SpecificationDto spec = new SpecificationDto();
        spec.setAConditions(java.util.Arrays.stream(deviceIds).map(RuleInfluenceScopeTest::condition).toList());
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());
        return spec;
    }

    private static SpecConditionDto condition(String deviceId) {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setDeviceId(deviceId);
        condition.setTargetType("state");
        condition.setRelation("=");
        condition.setValue("on");
        return condition;
    }
}
