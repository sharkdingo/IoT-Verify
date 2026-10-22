package cn.edu.nju.Iot_Verify.component.nusmv.generator.module;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SmvSpecificationBuilder spec negation (Salus §5).
 */
class SmvSpecificationBuilderNegationTest {

    private final SmvSpecificationBuilder builder = new SmvSpecificationBuilder();

    @Test
    void responseTemplatesEmitReachabilityDiagnosticsButNegatedSearchDoesNot() {
        for (String template : List.of("4", "5", "6")) {
            SpecificationDto spec = buildSpec(template, null,
                    List.of(buildCondition("sensor_1", "state", null, "=", "active")),
                    List.of(buildCondition("sensor_1", "state", null, "=", "idle")));
            String generated = builder.build(List.of(spec), buildDeviceMap(), false);
            assertTrue(generated.contains("CTLSPEC !EF(sensor_1.SensorState=active)"), generated);
            assertEquals(2, generated.lines().filter(line -> line.contains("SPEC ")).count());
            assertFalse(builder.generateNegatedSpec(spec, buildDeviceMap()).contains("\n"));
        }
    }

    private SpecificationDto buildSpec(String templateId,
                                        List<SpecConditionDto> aConditions,
                                        List<SpecConditionDto> ifConditions,
                                        List<SpecConditionDto> thenConditions) {
        SpecificationDto spec = new SpecificationDto();
        spec.setId("test-spec");
        spec.setTemplateId(templateId);
        spec.setAConditions(aConditions);
        spec.setIfConditions(ifConditions);
        spec.setThenConditions(thenConditions);
        return spec;
    }

    private SpecConditionDto buildCondition(String deviceId, String targetType, String key,
                                             String relation, String value) {
        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId(deviceId);
        cond.setTargetType(targetType);
        cond.setKey(key);
        cond.setRelation(relation);
        cond.setValue(value);
        return cond;
    }

    private Map<String, DeviceSmvData> buildDeviceMap() {
        // Minimal device with a state for condition resolution
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("sensor_1");
        smv.setModuleName("SensorModule");
        smv.setModes(List.of("SensorState"));
        smv.setModeStates(Map.of("SensorState", List.of("active", "idle")));
        return Map.of("sensor_1", smv);
    }

    @Test
    void generateSpecString_resolvesCanonicalDeviceIdWhenLabelIsDisplayOnly() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("ac_1");
        smv.setModuleName("AcModule");
        smv.setModes(List.of("AcState"));
        smv.setModeStates(Map.of("AcState", List.of("on", "off")));

        SpecConditionDto cond = buildCondition("ac_1", "state", null, "=", "on");
        cond.setDeviceLabel("LivingRoomAC");
        SpecificationDto spec = buildSpec("1", List.of(cond), null, null);

        String generated = builder.generateSpecString(
                spec, Map.of("ac_1", smv));

        assertTrue(generated.contains("ac_1.AcState=on"));
    }

    // ===== Template 1: always AG(A) → EF(!A) =====

    @Test
    void negate_template1_always() {
        SpecConditionDto cond = buildCondition("sensor_1", "state", null, "=", "active");
        SpecificationDto spec = buildSpec("1", List.of(cond), null, null);

        String negated = builder.generateNegatedSpec(spec, buildDeviceMap());

        assertTrue(negated.startsWith("CTLSPEC EF(!("));
        assertTrue(negated.contains("sensor_1.SensorState=active"));
    }

    @Test
    void template1_withIfThenOnly_rejectsHiddenImplicationForm() {
        SpecConditionDto ifCond = buildCondition("sensor_1", "state", null, "=", "active");
        SpecConditionDto thenCond = buildCondition("sensor_1", "state", null, "=", "idle");
        SpecificationDto spec = buildSpec("1", null, List.of(ifCond), List.of(thenCond));

        assertThrows(SmvSpecificationBuilder.InvalidConditionException.class,
                () -> builder.generateNegatedSpec(spec, buildDeviceMap()));
    }

    // ===== Template 2: eventually AF(A) → EG(!A) =====

    @Test
    void negate_template2_eventually() {
        SpecConditionDto cond = buildCondition("sensor_1", "state", null, "=", "active");
        SpecificationDto spec = buildSpec("2", List.of(cond), null, null);

        String negated = builder.generateNegatedSpec(spec, buildDeviceMap());

        assertTrue(negated.startsWith("CTLSPEC EG(!("));
    }

    // ===== Template 3: never AG(!A) → EF(A) =====

    @Test
    void negate_template3_never() {
        SpecConditionDto cond = buildCondition("sensor_1", "state", null, "=", "active");
        SpecificationDto spec = buildSpec("3", List.of(cond), null, null);

        String negated = builder.generateNegatedSpec(spec, buildDeviceMap());

        assertTrue(negated.startsWith("CTLSPEC EF("));
        assertFalse(negated.contains("!(")); // Not negated — EF(A) not EF(!A)
    }

    // ===== Template 4: immediate AG(IF→AX(THEN)) → EF(IF & EX(!THEN)) =====

    @Test
    void negate_template4_immediate() {
        SpecConditionDto ifCond = buildCondition("sensor_1", "state", null, "=", "active");
        SpecConditionDto thenCond = buildCondition("sensor_1", "state", null, "=", "idle");
        SpecificationDto spec = buildSpec("4", null, List.of(ifCond), List.of(thenCond));

        String negated = builder.generateNegatedSpec(spec, buildDeviceMap());

        assertTrue(negated.startsWith("CTLSPEC EF("));
        assertTrue(negated.contains("EX(!("));
    }

    // ===== Template 5: response AG(IF→AF(THEN)) → EF(IF & EG(!THEN)) =====

    @Test
    void negate_template5_response() {
        SpecConditionDto ifCond = buildCondition("sensor_1", "state", null, "=", "active");
        SpecConditionDto thenCond = buildCondition("sensor_1", "state", null, "=", "idle");
        SpecificationDto spec = buildSpec("5", null, List.of(ifCond), List.of(thenCond));

        String negated = builder.generateNegatedSpec(spec, buildDeviceMap());

        assertTrue(negated.startsWith("CTLSPEC EF("));
        assertTrue(negated.contains("EG(!("));
    }

    // ===== Template 6: persistence G(IF→FG(THEN)) → F(IF & GF(!THEN)) =====

    @Test
    void negate_template6_persistence() {
        SpecConditionDto ifCond = buildCondition("sensor_1", "state", null, "=", "active");
        SpecConditionDto thenCond = buildCondition("sensor_1", "state", null, "=", "idle");
        SpecificationDto spec = buildSpec("6", null, List.of(ifCond), List.of(thenCond));

        String negated = builder.generateNegatedSpec(spec, buildDeviceMap());

        assertTrue(negated.startsWith("LTLSPEC F("));
        assertTrue(negated.contains("G F(!("));
    }

    // ===== Template 7: safety AG !(body) → EF(body) =====

    @Test
    void negate_template7_safety_noAttack() {
        SpecConditionDto cond = buildCondition("sensor_1", "state", null, "=", "active");
        SpecificationDto spec = buildSpec("7", List.of(cond), null, null);

        String negated = builder.generateNegatedSpec(spec, buildDeviceMap());

        assertTrue(negated.startsWith("CTLSPEC EF("));
        assertTrue(negated.contains("sensor_1.SensorState=active"));
        // Should NOT contain negation — EF(body), not EF(!body)
        assertFalse(negated.contains("!("));
    }

    @Test
    void negate_template7_safety_withAttack() {
        SpecConditionDto cond = buildCondition("sensor_1", "state", null, "=", "active");
        SpecificationDto spec = buildSpec("7", List.of(cond), null, null);

        String negated = builder.generateNegatedSpec(spec, buildDeviceMap());

        assertTrue(negated.startsWith("CTLSPEC EF("));
        assertTrue(negated.contains("sensor_1.SensorState=active"));
    }

    @Test
    void template7_multiModeStateUsesEveryContributingReliabilityLabel() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("ac_1");
        smv.setModes(List.of("Power", "Mode"));
        smv.setModeStates(Map.of(
                "Power", List.of("off", "on"),
                "Mode", List.of("idle", "cool")));
        SpecConditionDto condition = buildCondition("ac_1", "state", "state", "=", "on;cool");
        SpecificationDto spec = buildSpec("7", List.of(condition), null, null);

        String generated = builder.generateSpecString(spec, Map.of("ac_1", smv));

        assertEquals("CTLSPEC AG !((ac_1.Power=on & ac_1.Mode=cool) & "
                + "(ac_1.trust_Power_on=untrusted | ac_1.trust_Mode_cool=untrusted))", generated);
        assertFalse(generated.contains("trust_Power_on_Mode_cool"));
    }

    @Test
    void multiModeStateUnderscoreWildcardConstrainsOnlyConcreteMode() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("ac_1");
        smv.setModes(List.of("Power", "Mode"));
        smv.setModeStates(Map.of(
                "Power", List.of("off", "on"),
                "Mode", List.of("idle", "cool")));
        SpecConditionDto condition = buildCondition("ac_1", "state", "state", "=", "on;_");
        SpecificationDto spec = buildSpec("1", List.of(condition), null, null);

        String generated = builder.generateSpecString(spec, Map.of("ac_1", smv));

        assertEquals("CTLSPEC AG(ac_1.Power=on)", generated);
    }

    @Test
    void template7_multipleConditionsAreTaintedByAnyUntrustedSource() {
        DeviceManifest.InternalVariable temperature = new DeviceManifest.InternalVariable();
        temperature.setName("temperature");
        temperature.setIsInside(true);
        DeviceManifest.InternalVariable humidity = new DeviceManifest.InternalVariable();
        humidity.setName("humidity");
        humidity.setIsInside(true);

        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("sensor_1");
        smv.setVariables(List.of(temperature, humidity));
        // Both declarations are device-local (IsInside=true above), so `reported` is the only meaningful
        // source and it is what the asserted `sensor_1.` identifiers require.
        SpecConditionDto hot = buildCondition("sensor_1", "variable", "temperature", ">", "30");
        hot.setVariableSource("reported");
        SpecConditionDto humid = buildCondition("sensor_1", "variable", "humidity", ">", "70");
        humid.setVariableSource("reported");
        SpecificationDto spec = buildSpec("7", List.of(hot, humid), null, null);

        String generated = builder.generateSpecString(spec, Map.of("sensor_1", smv));

        assertEquals("CTLSPEC AG !(sensor_1.temperature>30 & sensor_1.humidity>70 & "
                + "(sensor_1.trust_temperature=untrusted | sensor_1.trust_humidity=untrusted))", generated);
    }

    @Test
    void template7_signalApiUsesEveryModeChangedByItsEndState() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("ac_1");
        smv.setModes(List.of("Power", "Mode"));
        smv.setModeStates(Map.of(
                "Power", List.of("off", "on"),
                "Mode", List.of("idle", "cool")));
        DeviceManifest manifest = new DeviceManifest();
        manifest.setApis(List.of(DeviceManifest.API.builder()
                .name("startCooling")
                .signal(true)
                .endState("on;cool")
                .build()));
        smv.setManifest(manifest);
        SpecConditionDto condition = buildCondition("ac_1", "api", "startCooling", "=", "TRUE");
        SpecificationDto spec = buildSpec("7", List.of(condition), null, null);

        String generated = builder.generateSpecString(spec, Map.of("ac_1", smv));

        assertEquals("CTLSPEC AG !(ac_1.startCooling_a=TRUE & "
                + "(ac_1.trust_Power_on=untrusted | ac_1.trust_Mode_cool=untrusted))", generated);
    }

    @Test
    void template7_signalApiWithoutModeledEndStateFailsClosed() {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("service_1");
        smv.setModes(List.of("Status"));
        smv.setModeStates(Map.of("Status", List.of("idle")));
        DeviceManifest manifest = new DeviceManifest();
        manifest.setApis(List.of(DeviceManifest.API.builder()
                .name("notify")
                .signal(true)
                .build()));
        smv.setManifest(manifest);
        SpecConditionDto condition = buildCondition("service_1", "api", "notify", "=", "TRUE");
        SpecificationDto spec = buildSpec("7", List.of(condition), null, null);

        SmvSpecificationBuilder.InvalidConditionException error = assertThrows(
                SmvSpecificationBuilder.InvalidConditionException.class,
                () -> builder.generateSpecString(spec, Map.of("service_1", smv)));

        assertTrue(error.getMessage().contains("cannot resolve the MEDIC control-source label"));
    }

    @Test
    void template7_rejectsExplicitPrivacyPredicateInsteadOfChangingItsMeaning() {
        SpecConditionDto cond = buildCondition("sensor_1", "privacy", "SensorState_active", "=", "private");
        SpecificationDto spec = buildSpec("7", List.of(cond), null, null);

        SmvSpecificationBuilder.InvalidConditionException error = assertThrows(
                SmvSpecificationBuilder.InvalidConditionException.class,
                () -> builder.generateSpecString(spec, buildDeviceMap()));

        assertTrue(error.getMessage().contains("protected event or value"));
    }

    @Test
    void template7_rejectsNegativeStateRelationWhoseTrustLabelWouldTargetTheExcludedState() {
        SpecConditionDto cond = buildCondition("sensor_1", "state", null, "!=", "idle");
        SpecificationDto spec = buildSpec("7", List.of(cond), null, null);

        SmvSpecificationBuilder.InvalidConditionException error = assertThrows(
                SmvSpecificationBuilder.InvalidConditionException.class,
                () -> builder.generateSpecString(spec, buildDeviceMap()));

        assertTrue(error.getMessage().contains("require '='"));
    }

    // ===== buildNegated integration =====

    @Test
    void buildNegated_emitsOnlyNegatedSpec() {
        SpecConditionDto cond = buildCondition("sensor_1", "state", null, "=", "active");
        SpecificationDto spec1 = buildSpec("1", List.of(cond), null, null);
        SpecificationDto spec2 = buildSpec("3", List.of(cond), null, null);

        String result = builder.buildNegated(List.of(spec1, spec2), 0,
                buildDeviceMap(), false);

        assertTrue(result.contains("Negated specification (index 0)"));
        assertTrue(result.contains("CTLSPEC EF(!("));
        // Should NOT contain the second spec
        assertFalse(result.contains("AG !("));
    }

    @Test
    void buildNegated_invalidIndex_failsClosed() {
        assertThrows(SmvGenerationException.class,
                () -> builder.buildNegated(List.of(), 0, Map.of(), false));
        assertThrows(SmvGenerationException.class,
                () -> builder.buildNegated(null, 0, Map.of(), false));
    }
}
