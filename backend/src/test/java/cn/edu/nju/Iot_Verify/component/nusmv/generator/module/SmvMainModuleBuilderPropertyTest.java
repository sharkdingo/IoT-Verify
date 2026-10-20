package cn.edu.nju.Iot_Verify.component.nusmv.generator.module;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.PropertyDimension;
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
        device.setManifest(DeviceManifest.builder()
                .internalVariables(List.of(temperature, humidity))
                .build());
        device.setVariables(List.of(temperature, humidity));
        return device;
    }

    private String invokeCurrentStatePredicate(DeviceSmvData device,
                                               PropertyDimension dimension,
                                               String targetMode) throws Exception {
        Method method = SmvMainModuleBuilder.class.getDeclaredMethod(
                "buildCurrentStatePropertyPredicate",
                DeviceSmvData.class, String.class, PropertyDimension.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(builder, device, device.getVarName(), dimension, targetMode);
    }

    private String invokeRulePropertyConditions(RuleDto rule,
                                                Map<String, DeviceSmvData> devices,
                                                PropertyDimension dimension) throws Exception {
        Method method = SmvMainModuleBuilder.class.getDeclaredMethod(
                "appendRulePropertyConditions",
                StringBuilder.class, RuleDto.class, Map.class, PropertyDimension.class,
                SmvGenerationContext.class);
        method.setAccessible(true);
        StringBuilder result = new StringBuilder();
        method.invoke(builder, result, rule, devices, dimension, SmvGenerationContext.noop());
        return result.toString();
    }

    private String invokePropertyTransitions(DeviceSmvData target,
                                             List<RuleDto> rules,
                                             Map<String, DeviceSmvData> devices,
                                             PropertyDimension dimension) throws Exception {
        Method method = java.util.Arrays.stream(SmvMainModuleBuilder.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("appendPropertyTransitions"))
                .findFirst()
                .orElseThrow();
        method.setAccessible(true);
        DeviceVerificationDto targetDto = new DeviceVerificationDto();
        targetDto.setVarName(target.getVarName());
        StringBuilder result = new StringBuilder();
        Method grouping = SmvMainModuleBuilder.class.getDeclaredMethod(
                "groupRulesByResolvedTarget", List.class, Map.class);
        grouping.setAccessible(true);
        method.invoke(builder, result, List.of(targetDto), grouping.invoke(builder, rules, devices), devices,
                false, dimension, null, SmvGenerationContext.noop(), new LinkedHashMap<>());
        return result.toString();
    }

    @Test
    void buildsRecomputeBranchesForChangedInputsAndWarningContexts() {
        DeviceSmvData source = buildVariableDevice();
        source.setModuleName("Sensor");
        DeviceSmvData target = buildRuleTarget(List.of(DeviceManifest.API.builder()
                .name("on").endState("on").signal(false).build()));
        target.setModuleName("Target");
        Map<String, DeviceSmvData> model = Map.of(source.getVarName(), source, target.getVarName(), target);
        DeviceVerificationDto sourceDto = new DeviceVerificationDto();
        sourceDto.setVarName(source.getVarName());
        DeviceVerificationDto targetDto = new DeviceVerificationDto();
        targetDto.setVarName(target.getVarName());
        List<DeviceVerificationDto> devices = List.of(sourceDto, targetDto);
        RuleDto rule = commandRule("temperature", "on");
        List<RuleDto> rules = List.of(rule);

        String initial = builder.build(1L, devices, rules, model, AttackScenarioDto.none(), false);
        assertTrue(initial.replace(" ", "").contains("sensor_1.temperature>30"));
        rule.getConditions().get(0).setValue("31");
        String changed = builder.build(1L, devices, rules, model, AttackScenarioDto.none(), true);
        assertTrue(changed.replace(" ", "").contains("sensor_1.temperature>31"));
        assertFalse(changed.replace(" ", "").contains("sensor_1.temperature>30"));
        assertTrue(changed.contains("next(target_1.privacy_Power_on)"));

        rule.getCommand().setAction("missing");
        SmvGenerationContext invalidContext = SmvGenerationContext.collecting();
        String disabled = builder.build(1L, devices, rules, model, AttackScenarioDto.none(), true, invalidContext);
        assertEquals(1, invalidContext.warningsSnapshot().disabledRuleCount());
        assertEquals(1, invalidContext.warningsSnapshot().generationIssues().size());
        assertTrue(disabled.contains("next(iot_verify_rule_fired_0) := FALSE;"));

        rule.getCommand().setAction("on");
        SmvGenerationContext validContext = SmvGenerationContext.collecting();
        String restored = builder.build(1L, devices, rules, model, AttackScenarioDto.none(), true, validContext);
        assertEquals(0, validContext.warningsSnapshot().disabledRuleCount());
        assertTrue(restored.replace(" ", "").contains("sensor_1.temperature>31"));
    }

    private static DeviceSmvData buildRuleTarget(List<DeviceManifest.API> apis) {
        DeviceSmvData target = new DeviceSmvData();
        target.setVarName("target_1");
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
        return RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1")
                        .targetType("variable")
                        .attribute(sourceAttribute)
                        .relation(">")
                        .value("30")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("target_1")
                        .action(action)
                        .build())
                .build();
    }

    @Test
    @DisplayName("State property labels are guarded by the actual current state")
    void currentStatePredicate_guardsEveryLabel() throws Exception {
        String result = invokeCurrentStatePredicate(
                buildMultiModeDevice(), PropertyDimension.TRUST, "Power");

        assertTrue(result.contains("aircon_1.Power=on & aircon_1.trust_Power_on=trusted"));
        assertTrue(result.contains("aircon_1.Power=off & aircon_1.trust_Power_off=trusted"));
        assertTrue(result.contains(" | "));
        assertFalse(result.contains("trust_Fan_"));
    }

    @Test
    @DisplayName("State privacy uses the current state and private active label")
    void currentStatePrivacy_usesPrivatePredicate() throws Exception {
        String result = invokeCurrentStatePredicate(
                buildMultiModeDevice(), PropertyDimension.PRIVACY, null);

        assertTrue(result.contains("aircon_1.Power=on & aircon_1.privacy_Power_on=private"));
        assertTrue(result.contains("aircon_1.Fan=high & aircon_1.privacy_Fan_high=private"));
        assertTrue(result.contains(") | ("));
    }

    @Test
    @DisplayName("Multi-mode state trust retains control when any referenced mode is trusted")
    void currentStateTrust_acrossModesUsesOr() throws Exception {
        String result = invokeCurrentStatePredicate(
                buildMultiModeDevice(), PropertyDimension.TRUST, null);

        assertTrue(result.contains("aircon_1.trust_Power_on=trusted"));
        assertTrue(result.contains("aircon_1.trust_Fan_high=trusted"));
        assertTrue(result.contains(") | ("));
    }

    @Test
    @DisplayName("A partial full-state condition propagates only the mode it reads")
    void partialStateCondition_usesOnlyReferencedMode() throws Exception {
        DeviceSmvData device = buildMultiModeDevice();
        RuleDto rule = RuleDto.builder().conditions(List.of(
                RuleDto.Condition.builder()
                        .deviceName("aircon_1").targetType("state")
                        .attribute("state").relation("=").value("on").build()
        )).build();

        String result = invokeRulePropertyConditions(
                rule, Map.of("aircon_1", device), PropertyDimension.TRUST);

        assertTrue(result.contains("trust_Power_on=trusted"));
        assertFalse(result.contains("trust_Fan_"));
    }

    @Test
    @DisplayName("An underscore tuple wildcard is compiled like an empty tuple segment")
    void underscoreWildcardStateCondition_usesOnlyConcreteMode() throws Exception {
        DeviceSmvData device = buildMultiModeDevice();
        RuleDto rule = RuleDto.builder().conditions(List.of(
                RuleDto.Condition.builder()
                        .deviceName("aircon_1").targetType("state")
                        .attribute("state").relation("=").value("on;_").build()
        )).build();

        String result = invokeRulePropertyConditions(
                rule, Map.of("aircon_1", device), PropertyDimension.TRUST);

        assertTrue(result.contains("trust_Power_on=trusted"));
        assertFalse(result.contains("trust_Fan_"));
    }

    @Test
    @DisplayName("A compound state condition combines trust and privacy with MEDIC semantics")
    void compoundStateCondition_combinesLabelsByDimension() throws Exception {
        DeviceSmvData device = buildMultiModeDevice();
        RuleDto rule = RuleDto.builder().conditions(List.of(
                RuleDto.Condition.builder()
                        .deviceName("aircon_1").targetType("state")
                        .attribute("state").relation("=").value("on;high").build()
        )).build();

        String trust = invokeRulePropertyConditions(
                rule, Map.of("aircon_1", device), PropertyDimension.TRUST);
        String privacy = invokeRulePropertyConditions(
                rule, Map.of("aircon_1", device), PropertyDimension.PRIVACY);

        assertTrue(trust.contains(") | ("));
        assertTrue(privacy.contains(") | ("));
    }

    @Test
    @DisplayName("A multi-mode API event propagates every changed state's label")
    void multiModeApiCondition_combinesChangedStateLabels() throws Exception {
        DeviceSmvData device = buildMultiModeDevice();
        device.setManifest(DeviceManifest.builder()
                .modes(device.getModes())
                .apis(List.of(DeviceManifest.API.builder()
                        .name("set profile")
                        .endState("on;high")
                        .signal(true)
                        .build()))
                .build());
        RuleDto rule = RuleDto.builder().conditions(List.of(
                RuleDto.Condition.builder()
                        .deviceName("aircon_1").targetType("api")
                        .attribute("set profile").build()
        )).build();

        String trust = invokeRulePropertyConditions(
                rule, Map.of("aircon_1", device), PropertyDimension.TRUST);
        String privacy = invokeRulePropertyConditions(
                rule, Map.of("aircon_1", device), PropertyDimension.PRIVACY);

        assertEquals("(aircon_1.trust_Power_on=trusted | aircon_1.trust_Fan_high=trusted)", trust);
        assertEquals("(aircon_1.privacy_Power_on=private | aircon_1.privacy_Fan_high=private)", privacy);
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
    void trustConditions_retainControlWhenAnySourceIsTrusted() throws Exception {
        DeviceSmvData device = buildVariableDevice();
        RuleDto rule = RuleDto.builder().conditions(List.of(
                RuleDto.Condition.builder()
                        .deviceName("sensor_1").targetType("variable")
                        .attribute("temperature").relation(">").value("30").build(),
                RuleDto.Condition.builder()
                        .deviceName("sensor_1").targetType("variable")
                        .attribute("humidity").relation(">").value("70").build()
        )).build();

        String result = invokeRulePropertyConditions(
                rule, Map.of("sensor_1", device), PropertyDimension.TRUST);

        assertEquals("sensor_1.trust_temperature=trusted | sensor_1.trust_humidity=trusted", result);
    }

    @Test
    @DisplayName("MEDIC privacy propagation is private when any source is private")
    void privacyConditions_combinePrivatePredicatesWithOr() throws Exception {
        DeviceSmvData device = buildVariableDevice();
        RuleDto rule = RuleDto.builder().conditions(List.of(
                RuleDto.Condition.builder()
                        .deviceName("sensor_1").targetType("variable")
                        .attribute("temperature").relation(">").value("30").build(),
                RuleDto.Condition.builder()
                        .deviceName("sensor_1").targetType("variable")
                        .attribute("humidity").relation(">").value("70").build()
        )).build();

        String result = invokeRulePropertyConditions(
                rule, Map.of("sensor_1", device), PropertyDimension.PRIVACY);

        assertEquals("sensor_1.privacy_temperature=private | sensor_1.privacy_humidity=private", result);
    }

    @Test
    @DisplayName("Property labels use the same API start-state guard as the selected command")
    void propertyTransition_requiresTheCommandApiStartState() throws Exception {
        DeviceSmvData source = buildVariableDevice();
        DeviceSmvData target = buildRuleTarget(List.of(DeviceManifest.API.builder()
                .name("turn on").startState("off").endState("on").build()));

        String result = invokePropertyTransitions(target,
                List.of(commandRule("temperature", "turn on")),
                Map.of("sensor_1", source, "target_1", target),
                PropertyDimension.PRIVACY);
        String onBlock = result.substring(result.indexOf("next(target_1.privacy_Power_on)"));

        assertTrue(onBlock.contains("target_1.Power=off"), onBlock);
    }

    @Test
    @DisplayName("A multi-mode API requires its complete start-state tuple")
    void propertyTransition_requiresEveryApiStartStateComponent() throws Exception {
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

        String result = invokePropertyTransitions(target,
                List.of(commandRule("temperature", "activate profile")),
                Map.of("sensor_1", source, "target_1", target),
                PropertyDimension.PRIVACY);
        String onBlock = result.substring(result.indexOf("next(target_1.privacy_Power_on)"));

        assertTrue(onBlock.contains("target_1.Power=off"), onBlock);
        assertTrue(onBlock.contains("target_1.Fan=low"), onBlock);
    }

    @Test
    @DisplayName("A later rule cannot update labels when an earlier rule won the target mode")
    void propertyTransition_excludesEarlierCompetingRuleBranches() throws Exception {
        DeviceSmvData source = buildVariableDevice();
        DeviceSmvData target = buildRuleTarget(List.of(
                DeviceManifest.API.builder().name("turn off").endState("off").build(),
                DeviceManifest.API.builder().name("turn on").endState("on").build()));

        String result = invokePropertyTransitions(target,
                List.of(commandRule("temperature", "turn off"), commandRule("humidity", "turn on")),
                Map.of("sensor_1", source, "target_1", target),
                PropertyDimension.PRIVACY);
        String onBlock = result.substring(result.indexOf("next(target_1.privacy_Power_on)"));

        assertTrue(onBlock.contains("sensor_1.humidity>30"), onBlock);
        assertTrue(onBlock.contains("!((sensor_1.temperature>30))"), onBlock);
    }

    @Test
    @DisplayName("An overlapping multi-mode API action is never partially applied")
    void propertyTransition_blocksWholeLowerActionWhenAnyModeOverlaps() throws Exception {
        DeviceSmvData source = buildVariableDevice();
        DeviceSmvData target = buildMultiModeDevice();
        target.setVarName("target_1");
        target.setManifest(DeviceManifest.builder()
                .modes(target.getModes())
                .apis(List.of(
                        DeviceManifest.API.builder().name("power off").endState("off;").build(),
                        DeviceManifest.API.builder().name("activate profile").endState("on;high").build()))
                .build());

        String result = invokePropertyTransitions(target,
                List.of(commandRule("temperature", "power off"),
                        commandRule("humidity", "activate profile")),
                Map.of("sensor_1", source, "target_1", target),
                PropertyDimension.PRIVACY);
        String highBlock = result.substring(result.indexOf("next(target_1.privacy_Fan_high)"));

        assertTrue(highBlock.contains("sensor_1.humidity>30"), highBlock);
        assertTrue(highBlock.contains("!((sensor_1.temperature>30))"), highBlock);
    }

    @Test
    @DisplayName("No source conditions fail closed")
    void noConditions_returnsFalse() throws Exception {
        RuleDto rule = RuleDto.builder().conditions(List.of()).build();
        assertEquals("FALSE", invokeRulePropertyConditions(rule, Map.of(), PropertyDimension.TRUST));
    }

    @Test
    @DisplayName("Unsupported source semantics fail closed")
    void unsupportedSource_returnsFalse() throws Exception {
        DeviceSmvData device = buildVariableDevice();
        RuleDto rule = RuleDto.builder().conditions(List.of(
                RuleDto.Condition.builder()
                        .deviceName("sensor_1").targetType("unknown")
                        .attribute("temperature").relation("=").value("1").build()
        )).build();

        assertEquals("FALSE", invokeRulePropertyConditions(
                rule, Map.of("sensor_1", device), PropertyDimension.TRUST));
    }
}
