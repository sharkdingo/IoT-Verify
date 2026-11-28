package cn.edu.nju.Iot_Verify.component.nusmv.generator.module;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerationContext;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for MEDIC trust/privacy propagation predicates. */
class SmvMainModuleBuilderPropertyTest {

    private SmvMainModuleBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SmvMainModuleBuilder();
    }

    private static DeviceSmvData buildMultiModeDevice() {
        DeviceSmvData device = new DeviceSmvData();
        device.setVarName("aircon_1");
        device.setModuleName("Aircon");
        device.setModes(List.of("Power", "Fan"));
        Map<String, List<String>> states = new LinkedHashMap<>();
        states.put("Power", List.of("on", "off"));
        states.put("Fan", List.of("high", "low"));
        device.setModeStates(states);
        device.setManifest(DeviceManifest.builder().modes(device.getModes()).build());
        return device;
    }

    private static DeviceSmvData buildVariableDevice() {
        DeviceManifest.InternalVariable temperature = DeviceManifest.InternalVariable.builder()
                .name("temperature")
                .isInside(true)
                .lowerBound(0)
                .upperBound(100)
                .build();
        DeviceManifest.InternalVariable humidity = DeviceManifest.InternalVariable.builder()
                .name("humidity")
                .isInside(true)
                .lowerBound(0)
                .upperBound(100)
                .build();
        DeviceSmvData device = new DeviceSmvData();
        device.setVarName("sensor_1");
        device.setModuleName("Sensor");
        device.setManifest(DeviceManifest.builder()
                .internalVariables(List.of(temperature, humidity))
                .build());
        device.setVariables(List.of(temperature, humidity));
        return device;
    }

    private String labelModel(DeviceSmvData source, RuleDto.Condition... conditions) {
        DeviceSmvData target = buildRuleTarget(List.of(DeviceManifest.API.builder()
                .name("turn on").endState("on").build()));
        return buildPropertyModel(source, target, commandRule("turn on", conditions));
    }

    private String buildPropertyModel(DeviceSmvData source, DeviceSmvData target, RuleDto... rules) {
        return builder.build(1L, List.of(device(source), device(target)), null, List.of(rules),
                Map.of(source.getVarName(), source, target.getVarName(), target),
                AttackScenarioDto.none(), true, SmvGenerationContext.noop(), List.of());
    }

    private static DeviceVerificationDto device(DeviceSmvData smv) {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName(smv.getVarName());
        return device;
    }

    private static String propertyAssignment(String model, String variable) {
        int start = model.indexOf("next(" + variable + ") :=\n\tcase\n");
        assertTrue(start >= 0, model);
        int end = model.indexOf("\n\tesac;", start);
        assertTrue(end > start, model);
        return model.substring(start, end);
    }

    @Test
    void buildsRecomputeBranchesForChangedInputsAndWarningContexts() {
        DeviceSmvData source = buildVariableDevice();
        DeviceSmvData target = buildRuleTarget(List.of(DeviceManifest.API.builder()
                .name("on").endState("on").signal(false).build()));
        Map<String, DeviceSmvData> model = Map.of(source.getVarName(), source, target.getVarName(), target);
        List<DeviceVerificationDto> devices = List.of(device(source), device(target));
        RuleDto rule = commandRule("temperature", "on");
        List<RuleDto> rules = List.of(rule);

        String initial = builder.build(1L, devices, null, rules, model, AttackScenarioDto.none(), false,
                SmvGenerationContext.noop(), List.of());
        assertTrue(initial.replace(" ", "").contains("sensor_1.temperature>30"));
        rule.getConditions().get(0).setValue("31");
        String changed = builder.build(1L, devices, null, rules, model, AttackScenarioDto.none(), true,
                SmvGenerationContext.noop(), List.of());
        assertTrue(changed.replace(" ", "").contains("sensor_1.temperature>31"));
        assertFalse(changed.replace(" ", "").contains("sensor_1.temperature>30"));
        assertTrue(changed.contains("next(target_1.privacy_Power_on)"));

        rule.getCommand().setAction("missing");
        SmvGenerationContext invalidContext = SmvGenerationContext.collecting();
        String disabled = builder.build(1L, devices, null, rules, model, AttackScenarioDto.none(), true,
                invalidContext, List.of());
        assertEquals(1, invalidContext.warningsSnapshot().disabledRuleCount());
        assertEquals(1, invalidContext.warningsSnapshot().generationIssues().size());
        assertTrue(disabled.contains("next(iot_verify_rule_fired_0) := FALSE;"));

        rule.getCommand().setAction("on");
        SmvGenerationContext validContext = SmvGenerationContext.collecting();
        String restored = builder.build(1L, devices, null, rules, model, AttackScenarioDto.none(), true,
                validContext, List.of());
        assertEquals(0, validContext.warningsSnapshot().disabledRuleCount());
        assertTrue(restored.replace(" ", "").contains("sensor_1.temperature>31"));
    }

    private static DeviceSmvData buildRuleTarget(List<DeviceManifest.API> apis) {
        DeviceSmvData target = new DeviceSmvData();
        target.setVarName("target_1");
        target.setModuleName("Target");
        target.setSensor(false);
        target.setModes(List.of("Power"));
        target.setModeStates(Map.of("Power", List.of("off", "on")));
        target.setManifest(DeviceManifest.builder()
                .modes(target.getModes())
                .apis(apis)
                .build());
        return target;
    }

    private static RuleDto commandRule(String sourceAttribute, String action) {
        return commandRule(action, condition("sensor_1", "variable", sourceAttribute, ">", "30"));
    }

    private static RuleDto commandRule(String action, RuleDto.Condition... conditions) {
        return RuleDto.builder()
                .conditions(List.of(conditions))
                .command(RuleDto.Command.builder()
                        .deviceName("target_1")
                        .action(action)
                        .build())
                .build();
    }

    private static RuleDto.Condition condition(String source, String targetType, String attribute,
                                                String relation, String value) {
        return RuleDto.Condition.builder().deviceName(source).targetType(targetType)
                .attribute(attribute).relation(relation).value(value).build();
    }

    @Test
    @DisplayName("State property labels are guarded by the actual current state")
    void currentStatePredicate_guardsEveryLabel() {
        String model = labelModel(buildMultiModeDevice(), condition("aircon_1", "mode", "Power", "=", "on"));
        String result = propertyAssignment(model, "target_1.trust_Power_on");

        assertTrue(result.contains("aircon_1.Power=on & aircon_1.trust_Power_on=trusted"), result);
        assertTrue(result.contains("aircon_1.Power=off & aircon_1.trust_Power_off=trusted"), result);
        assertTrue(result.contains(" | "), result);
        assertFalse(result.contains("trust_Fan_"), result);
    }

    @Test
    @DisplayName("State privacy uses the current state and private active label")
    void currentStatePrivacy_usesPrivatePredicate() {
        String model = labelModel(buildMultiModeDevice(), condition("aircon_1", "state", "state", "=", "on;high"));
        String result = propertyAssignment(model, "target_1.privacy_Power_on");

        assertTrue(result.contains("aircon_1.Power=on & aircon_1.privacy_Power_on=private"), result);
        assertTrue(result.contains("aircon_1.Fan=high & aircon_1.privacy_Fan_high=private"), result);
        assertTrue(result.contains("privacy_Power_off=private)) | ((aircon_1.Fan=high"), result);
    }

    @Test
    @DisplayName("Multi-mode state trust retains control when any referenced mode is trusted")
    void currentStateTrust_acrossModesUsesOr() {
        String model = labelModel(buildMultiModeDevice(), condition("aircon_1", "state", "state", "=", "on;high"));
        String result = propertyAssignment(model, "target_1.trust_Power_on");

        assertTrue(result.contains("aircon_1.trust_Power_on=trusted"), result);
        assertTrue(result.contains("aircon_1.trust_Fan_high=trusted"), result);
        assertTrue(result.contains("trust_Power_off=trusted)) | ((aircon_1.Fan=high"), result);
    }

    @Test
    @DisplayName("A partial full-state condition propagates only the mode it reads")
    void partialStateCondition_usesOnlyReferencedMode() {
        String model = labelModel(buildMultiModeDevice(), condition("aircon_1", "state", "state", "=", "on"));
        String result = propertyAssignment(model, "target_1.trust_Power_on");

        assertTrue(result.contains("trust_Power_on=trusted"), result);
        assertFalse(result.contains("trust_Fan_"), result);
    }

    @Test
    @DisplayName("An underscore tuple wildcard is compiled like an empty tuple segment")
    void underscoreWildcardStateCondition_usesOnlyConcreteMode() {
        String model = labelModel(buildMultiModeDevice(), condition("aircon_1", "state", "state", "=", "on;_"));
        String result = propertyAssignment(model, "target_1.trust_Power_on");

        assertTrue(result.contains("trust_Power_on=trusted"), result);
        assertFalse(result.contains("trust_Fan_"), result);
    }

    @Test
    @DisplayName("A compound state condition combines trust and privacy with MEDIC semantics")
    void compoundStateCondition_combinesLabelsByDimension() {
        String model = labelModel(buildMultiModeDevice(), condition("aircon_1", "state", "state", "=", "on;high"));
        String trust = propertyAssignment(model, "target_1.trust_Power_on");
        String privacy = propertyAssignment(model, "target_1.privacy_Power_on");

        assertTrue(trust.contains(") | ("), trust);
        assertTrue(privacy.contains(") | ("), privacy);
    }

    @Test
    @DisplayName("A multi-mode API event propagates every changed state's label")
    void multiModeApiCondition_combinesChangedStateLabels() {
        DeviceSmvData device = buildMultiModeDevice();
        device.setManifest(DeviceManifest.builder()
                .modes(device.getModes())
                .apis(List.of(DeviceManifest.API.builder()
                        .name("set profile")
                        .endState("on;high")
                        .signal(true)
                        .build()))
                .build());
        String model = labelModel(device, condition("aircon_1", "api", "set profile", null, null));
        String trust = propertyAssignment(model, "target_1.trust_Power_on");
        String privacy = propertyAssignment(model, "target_1.privacy_Power_on");

        assertTrue(trust.contains(" & ((aircon_1.trust_Power_on=trusted | aircon_1.trust_Fan_high=trusted)): trusted;"), trust);
        assertTrue(privacy.contains(" & ((aircon_1.privacy_Power_on=private | aircon_1.privacy_Fan_high=private)): private;"), privacy);
    }

    @Test
    @DisplayName("A multi-mode API pulse observes the complete transition and a real change")
    void multiModeApiPulse_usesCompleteStartAndEndTuples() throws Exception {
        DeviceSmvData device = buildMultiModeDevice();
        Method method = SmvMainModuleBuilder.class.getDeclaredMethod(
                "buildStateEventCondition", DeviceSmvData.class, String.class, String.class, String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(
                builder, device, "aircon_1", "off;low", "on;high");

        assertTrue(result.contains("aircon_1.Power=off"), result);
        assertTrue(result.contains("aircon_1.Fan=low"), result);
        assertTrue(result.contains("next(aircon_1.Power)=on"), result);
        assertTrue(result.contains("next(aircon_1.Fan)=high"), result);
        assertTrue(result.contains("aircon_1.Power!=on | aircon_1.Fan!=high"), result);
    }

    @Test
    @DisplayName("MEDIC trust propagation is untrusted only when every source is untrusted")
    void trustConditions_retainControlWhenAnySourceIsTrusted() {
        String model = labelModel(buildVariableDevice(),
                condition("sensor_1", "variable", "temperature", ">", "30"),
                condition("sensor_1", "variable", "humidity", ">", "70"));
        String result = propertyAssignment(model, "target_1.trust_Power_on");

        assertTrue(result.contains(" & (sensor_1.trust_temperature=trusted | "
                + "sensor_1.trust_humidity=trusted): trusted;"), result);
    }

    @Test
    @DisplayName("MEDIC privacy propagation is private when any source is private")
    void privacyConditions_combinePrivatePredicatesWithOr() {
        String model = labelModel(buildVariableDevice(),
                condition("sensor_1", "variable", "temperature", ">", "30"),
                condition("sensor_1", "variable", "humidity", ">", "70"));
        String result = propertyAssignment(model, "target_1.privacy_Power_on");

        assertTrue(result.contains(" & (sensor_1.privacy_temperature=private | "
                + "sensor_1.privacy_humidity=private): private;"), result);
    }

    @Test
    @DisplayName("Property labels use the same API start-state guard as the selected command")
    void propertyTransition_requiresTheCommandApiStartState() {
        DeviceSmvData source = buildVariableDevice();
        DeviceSmvData target = buildRuleTarget(List.of(DeviceManifest.API.builder()
                .name("turn on").startState("off").endState("on").build()));

        String model = buildPropertyModel(source, target,
                commandRule("temperature", "turn on"));
        String onBlock = propertyAssignment(model, "target_1.privacy_Power_on");

        assertTrue(onBlock.contains("target_1.Power=off"), onBlock);
    }

    @Test
    @DisplayName("A multi-mode API requires its complete start-state tuple")
    void propertyTransition_requiresEveryApiStartStateComponent() {
        DeviceSmvData source = buildVariableDevice();
        DeviceSmvData target = buildMultiModeDevice();
        target.setVarName("target_1");
        target.setManifest(DeviceManifest.builder()
                .modes(target.getModes())
                .apis(List.of(DeviceManifest.API.builder()
                        .name("activate profile")
                        .startState("off;low")
                        .endState("on;high")
                        .build()))
                .build());

        String model = buildPropertyModel(source, target,
                commandRule("temperature", "activate profile"));
        String onBlock = propertyAssignment(model, "target_1.privacy_Power_on");

        assertTrue(onBlock.contains("target_1.Power=off"), onBlock);
        assertTrue(onBlock.contains("target_1.Fan=low"), onBlock);
    }

    @Test
    @DisplayName("A later rule cannot update labels when an earlier rule won the target mode")
    void propertyTransition_excludesEarlierCompetingRuleBranches() {
        DeviceSmvData source = buildVariableDevice();
        DeviceSmvData target = buildRuleTarget(List.of(
                DeviceManifest.API.builder().name("turn off").endState("off").build(),
                DeviceManifest.API.builder().name("turn on").endState("on").build()));

        String model = buildPropertyModel(source, target,
                commandRule("temperature", "turn off"), commandRule("humidity", "turn on"));
        String onBlock = propertyAssignment(model, "target_1.privacy_Power_on");

        assertTrue(onBlock.contains("sensor_1.humidity>30"), onBlock);
        assertTrue(onBlock.contains("!((sensor_1.temperature>30))"), onBlock);
    }

    @Test
    @DisplayName("An overlapping multi-mode API action is never partially applied")
    void propertyTransition_blocksWholeLowerActionWhenAnyModeOverlaps() {
        DeviceSmvData source = buildVariableDevice();
        DeviceSmvData target = buildMultiModeDevice();
        target.setVarName("target_1");
        target.setManifest(DeviceManifest.builder()
                .modes(target.getModes())
                .apis(List.of(
                        DeviceManifest.API.builder().name("power off").endState("off;").build(),
                        DeviceManifest.API.builder().name("activate profile").endState("on;high").build()))
                .build());

        String model = buildPropertyModel(source, target,
                commandRule("temperature", "power off"), commandRule("humidity", "activate profile"));
        String highBlock = propertyAssignment(model, "target_1.privacy_Fan_high");

        assertTrue(highBlock.contains("sensor_1.humidity>30"), highBlock);
        assertTrue(highBlock.contains("!((sensor_1.temperature>30))"), highBlock);
    }

    @Test
    @DisplayName("No source conditions fail closed")
    void noConditions_returnsFalse() {
        String model = labelModel(buildVariableDevice());
        String result = propertyAssignment(model, "target_1.trust_Power_on");

        assertTrue(result.contains(" & (FALSE): trusted;"), result);
    }

    @Test
    @DisplayName("Unsupported source semantics fail closed")
    void unsupportedSource_returnsFalse() {
        String model = labelModel(buildVariableDevice(), condition("sensor_1", "unknown", "temperature", "=", "1"));
        String result = propertyAssignment(model, "target_1.trust_Power_on");

        assertTrue(result.contains(" & (FALSE): trusted;"), result);
    }
}
