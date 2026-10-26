package cn.edu.nju.Iot_Verify.component.nusmv.generator.module;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SmvSpecificationBuilder error messages when variable conditions fail read-capability checks.
 * These tests verify that error messages accurately distinguish between device-local variables
 * (IsInside=true) and affect-only shared variables (Reads=false).
 */
class SmvSpecificationBuilderErrorMessageTest {

    private final SmvSpecificationBuilder builder = new SmvSpecificationBuilder();

    @Test
    void variableCondition_deviceLocal_shouldMentionIsInsideTrue() {
        // Device with a device-local variable (IsInside=true)
        DeviceManifest.InternalVariable localVar = new DeviceManifest.InternalVariable();
        localVar.setName("localCounter");
        localVar.setIsInside(true);  // Device-local
        localVar.setLowerBound(0);
        localVar.setUpperBound(100);

        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("device_1");
        smv.setModuleName("DeviceModule");
        smv.setModes(List.of("mode"));
        smv.setVariables(List.of(localVar));
        // envVariables is empty (device-local variables don't go there)

        SpecConditionDto cond = new SpecConditionDto();
        cond.setTargetType("variable");
        cond.setDeviceId("device_1");
        cond.setKey("localCounter");
        cond.setRelation(">");
        cond.setValue("50");
        cond.setVariableSource("environment");  // User mistakenly tries to reference environment

        SpecificationDto spec = new SpecificationDto();
        spec.setId("test-spec");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(cond));

        SmvSpecificationBuilder.InvalidConditionException ex = assertThrows(
                SmvSpecificationBuilder.InvalidConditionException.class,
                () -> builder.generateNegatedSpec(spec, Map.of("device_1", smv))
        );

        // Error message should mention "device-local" and "IsInside=true"
        assertTrue(ex.getMessage().contains("device-local"),
                "Error message should identify the variable as device-local");
        assertTrue(ex.getMessage().contains("IsInside=true"),
                "Error message should mention IsInside=true to guide the user");
        assertTrue(ex.getMessage().contains("variableSource=reported"),
                "Error message should suggest the correct solution");
    }

    @Test
    void variableCondition_affectOnly_shouldMentionReadsFalse() {
        // Device with an affect-only shared variable (IsInside=false, Reads=false)
        DeviceManifest.InternalVariable affectOnlyVar = new DeviceManifest.InternalVariable();
        affectOnlyVar.setName("temperature");
        affectOnlyVar.setIsInside(false);    // Shared
        affectOnlyVar.setReads(false);       // Affect-only
        affectOnlyVar.setLowerBound(0);
        affectOnlyVar.setUpperBound(100);

        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("heater");
        smv.setModuleName("HeaterModule");
        smv.setModes(List.of("mode"));
        smv.setVariables(List.of(affectOnlyVar));
        smv.setImpactedEnvironmentVariables(Map.of("temperature", affectOnlyVar));
        // envVariables is empty (Reads=false variables don't go there)

        SpecConditionDto cond = new SpecConditionDto();
        cond.setTargetType("variable");
        cond.setDeviceId("heater");
        cond.setKey("temperature");
        cond.setRelation(">");
        cond.setValue("25");
        cond.setVariableSource("environment");  // User tries to reference environment value

        SpecificationDto spec = new SpecificationDto();
        spec.setId("test-spec");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(cond));

        SmvSpecificationBuilder.InvalidConditionException ex = assertThrows(
                SmvSpecificationBuilder.InvalidConditionException.class,
                () -> builder.generateNegatedSpec(spec, Map.of("heater", smv))
        );

        // Error message should mention "affect-only" and "Reads=false"
        assertTrue(ex.getMessage().contains("affect-only"),
                "Error message should identify the variable as affect-only");
        assertTrue(ex.getMessage().contains("Reads=false"),
                "Error message should mention Reads=false to guide the user");
        assertTrue(ex.getMessage().contains("add a sensor device with Reads=true"),
                "Error message should suggest adding a sensor as a solution");

        // Should NOT mention "device-local" (that's a different issue)
        assertFalse(ex.getMessage().contains("device-local"),
                "Error message should not confuse affect-only with device-local");
    }

    @Test
    void variableCondition_undeclared_shouldIndicateMissingDeclaration() {
        // Device without the variable in InternalVariables at all
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("device_1");
        smv.setModuleName("DeviceModule");
        smv.setModes(List.of("mode"));
        smv.setVariables(List.of());  // No variables

        SpecConditionDto cond = new SpecConditionDto();
        cond.setTargetType("variable");
        cond.setDeviceId("device_1");
        cond.setKey("unknownVar");
        cond.setRelation("=");
        cond.setValue("10");
        cond.setVariableSource("environment");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("test-spec");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(cond));

        SmvSpecificationBuilder.InvalidConditionException ex = assertThrows(
                SmvSpecificationBuilder.InvalidConditionException.class,
                () -> builder.generateNegatedSpec(spec, Map.of("device_1", smv))
        );

        // Error message should indicate the variable is not declared
        assertTrue(ex.getMessage().contains("not declared"),
                "Error message should indicate the variable is not in InternalVariables");
    }

    @Test
    void variableCondition_readableShared_shouldSucceed() {
        // Device with a readable shared variable (IsInside=false, Reads=true)
        DeviceManifest.InternalVariable readableVar = new DeviceManifest.InternalVariable();
        readableVar.setName("temperature");
        readableVar.setIsInside(false);    // Shared
        readableVar.setReads(true);        // Readable
        readableVar.setLowerBound(0);
        readableVar.setUpperBound(100);

        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("sensor");
        smv.setModuleName("SensorModule");
        smv.setModes(List.of("mode"));
        smv.setVariables(List.of(readableVar));
        smv.setEnvVariables(Map.of("temperature", readableVar));  // Readable → in envVariables

        SpecConditionDto cond = new SpecConditionDto();
        cond.setTargetType("variable");
        cond.setDeviceId("sensor");
        cond.setKey("temperature");
        cond.setRelation(">");
        cond.setValue("25");
        cond.setVariableSource("environment");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("test-spec");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(cond));

        // Should not throw
        assertDoesNotThrow(() -> builder.generateNegatedSpec(spec, Map.of("sensor", smv)));
    }

    @Test
    void variableCondition_affectOnly_variableSourceReported_compilesToDeviceMirror() {
        // Affect-only variable with variableSource=reported compiles to device.temperature
        // (even though the device module won't declare that variable).
        // This is allowed at spec-compilation time; the inconsistency surfaces later at NuSMV parse time.
        DeviceManifest.InternalVariable affectOnlyVar = new DeviceManifest.InternalVariable();
        affectOnlyVar.setName("temperature");
        affectOnlyVar.setIsInside(false);
        affectOnlyVar.setReads(false);
        affectOnlyVar.setLowerBound(0);
        affectOnlyVar.setUpperBound(100);

        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("heater");
        smv.setModuleName("HeaterModule");
        smv.setModes(List.of("mode"));
        smv.setVariables(List.of(affectOnlyVar));
        smv.setImpactedEnvironmentVariables(Map.of("temperature", affectOnlyVar));

        SpecConditionDto cond = new SpecConditionDto();
        cond.setTargetType("variable");
        cond.setDeviceId("heater");
        cond.setKey("temperature");
        cond.setRelation(">");
        cond.setValue("25");
        cond.setVariableSource("reported");  // Compiles to heater.temperature

        SpecificationDto spec = new SpecificationDto();
        spec.setId("test-spec");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(cond));

        // Should not throw at spec-compilation time (gates at earlier boundaries should catch this)
        assertDoesNotThrow(() -> builder.generateNegatedSpec(spec, Map.of("heater", smv)));
    }
}
