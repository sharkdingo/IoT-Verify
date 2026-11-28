package cn.edu.nju.Iot_Verify.component.template;

import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for frozen-variable validation (local enum variables without driver mechanisms).
 */
class DeviceTemplateNuSmvValidatorFrozenVariableTest {

    // Manifest validation never reaches the generator; only runTemplateNuSmvPrecheck uses it.
    private final DeviceTemplateNuSmvValidator validator = new DeviceTemplateNuSmvValidator(null);

    @Test
    @DisplayName("a local enum without state dynamics needs a transition assignment")
    void testLocalEnumNeedsDriver() {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setName("Broken Sensor");

        // Local enum variable with no Dynamics, no Transition
        DeviceManifest.InternalVariable var = new DeviceManifest.InternalVariable();
        var.setName("status");
        var.setIsInside(true);
        var.setValues(List.of("idle", "active"));
        var.setTrust("untrusted");
        var.setPrivacy("public");
        var.setFalsifiableWhenCompromised(true);

        manifest.setInternalVariables(List.of(var));
        manifest.setModes(List.of());
        manifest.setInitState("");
        manifest.setWorkingStates(List.of());
        manifest.setTransitions(List.of());
        manifest.setApis(List.of());

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                validator.validateTemplateManifestForNuSmv("Broken Sensor", manifest));

        assertTrue(ex.getMessage().contains("has no driver mechanism"));
        assertTrue(ex.getMessage().contains("status"));

        manifest.setTransitions(List.of(DeviceManifest.Transition.builder()
                .name("activate")
                .trigger(DeviceManifest.Trigger.builder().attribute("status").relation("=").value("idle").build())
                .assignments(List.of(DeviceManifest.Assignment.builder().attribute("status").value("active").build()))
                .build()));
        assertDoesNotThrow(() -> validator.validateTemplateManifestForNuSmv("Broken Sensor", manifest));
    }

    @Test
    @DisplayName("accept local enum variable with WorkingState Dynamics")
    void testAcceptLocalEnumWithDynamics() {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setName("Good Sensor");

        DeviceManifest.InternalVariable var = new DeviceManifest.InternalVariable();
        var.setName("contact");
        var.setIsInside(true);
        var.setValues(List.of("closed", "open"));
        var.setTrust("untrusted");
        var.setPrivacy("private");
        var.setFalsifiableWhenCompromised(true);

        manifest.setInternalVariables(List.of(var));
        manifest.setModes(List.of("DoorState"));
        manifest.setInitState("closed");

        // WorkingStates with Dynamics
        DeviceManifest.WorkingState closed = new DeviceManifest.WorkingState();
        closed.setName("closed");
        closed.setTrust("untrusted");
        closed.setPrivacy("private");
        DeviceManifest.Dynamic dynamic1 = new DeviceManifest.Dynamic();
        dynamic1.setVariableName("contact");
        dynamic1.setValue("closed");
        closed.setDynamics(List.of(dynamic1));

        DeviceManifest.WorkingState open = new DeviceManifest.WorkingState();
        open.setName("open");
        open.setTrust("untrusted");
        open.setPrivacy("private");
        DeviceManifest.Dynamic dynamic2 = new DeviceManifest.Dynamic();
        dynamic2.setVariableName("contact");
        dynamic2.setValue("open");
        open.setDynamics(List.of(dynamic2));

        manifest.setWorkingStates(List.of(closed, open));
        manifest.setTransitions(List.of());
        manifest.setApis(List.of());

        assertDoesNotThrow(() -> validator.validateTemplateManifestForNuSmv("Good Sensor", manifest));
    }

    @Test
    @DisplayName("accept shared enum variable without driver (IsInside=false needs no Dynamics)")
    void testAcceptSharedEnumWithoutDriver() {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setName("Env Reader");

        // Shared variable - no driver needed
        DeviceManifest.InternalVariable var = new DeviceManifest.InternalVariable();
        var.setName("externalInput");
        var.setIsInside(false);  // Shared
        var.setReads(true);
        var.setValues(List.of("on", "off"));
        var.setTrust("untrusted");
        var.setPrivacy("public");
        var.setFalsifiableWhenCompromised(true);

        manifest.setInternalVariables(List.of(var));
        manifest.setModes(List.of());
        manifest.setInitState("");
        manifest.setWorkingStates(List.of());
        manifest.setTransitions(List.of());
        manifest.setApis(List.of());

        assertDoesNotThrow(() -> validator.validateTemplateManifestForNuSmv("Env Reader", manifest));
    }

    @Test
    @DisplayName("accept local numeric variable with NaturalChangeRate (no Dynamics required)")
    void testAcceptLocalNumericWithRate() {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setName("Thermometer");

        DeviceManifest.InternalVariable var = new DeviceManifest.InternalVariable();
        var.setName("temperature");
        var.setIsInside(true);
        var.setLowerBound(0);
        var.setUpperBound(100);
        var.setNaturalChangeRate("[-1, 1]");
        var.setTrust("untrusted");
        var.setPrivacy("public");
        var.setFalsifiableWhenCompromised(true);

        manifest.setInternalVariables(List.of(var));
        manifest.setModes(List.of());
        manifest.setInitState("");
        manifest.setWorkingStates(List.of());
        manifest.setTransitions(List.of());
        manifest.setApis(List.of());

        assertDoesNotThrow(() -> validator.validateTemplateManifestForNuSmv("Thermometer", manifest));
    }
}
