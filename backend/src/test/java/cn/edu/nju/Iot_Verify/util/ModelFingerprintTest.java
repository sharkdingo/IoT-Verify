package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter.ModelInputSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.core.JsonGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ModelFingerprintTest {
    private final ModelFingerprint fingerprint = new ModelFingerprint(new ObjectMapper().findAndRegisterModules());

    @Test
    void canonicalFingerprintsUseTheLogicalSpecificationShape() {
        var spec = new SpecificationDto();
        spec.setId("s1");
        var board = snapshot(List.of(spec), List.of());
        var pair = fingerprint.boardFingerprints(board);
        assertEquals("c889a292b6784a8377274f7774ef88b7b77d0bcb967e3469e76819c28b18010a", pair.verification());
        assertEquals("db23109e9940c050afd0e0ba51c845473e2eab78f497528f13cb9cc3d91da019", pair.simulation());
        assertEquals("fe6a502aeaa5ee1d70c9c548a5b5266424c4bc20cb492ad9a20142fdba364cc5",
                fingerprint.paperDomainFingerprint(board, 5));
    }

    @Test
    void pairedIdentitiesReuseCanonicalizationAndDoNotMutateTheSnapshot() {
        AtomicInteger deviceSerializations = new AtomicInteger();
        SimpleModule module = new SimpleModule();
        module.addSerializer(DeviceVerificationDto.class, new JsonSerializer<>() {
            @Override
            public void serialize(DeviceVerificationDto value, JsonGenerator output,
                                  SerializerProvider provider) throws IOException {
                deviceSerializations.incrementAndGet();
                output.writeStartObject();
                output.writeStringField("varName", value.getVarName());
                output.writeEndObject();
            }
        });
        ModelFingerprint counter = new ModelFingerprint(new ObjectMapper().registerModule(module));
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        var board = snapshot(List.of(spec), List.of());
        String expectedVerification = counter.modelFingerprint(board);
        String expectedSimulation = counter.simulationFingerprint(board);
        String before = JsonUtils.toJson(board);
        deviceSerializations.set(0);

        var pair = counter.boardFingerprints(board);

        assertEquals(expectedVerification, pair.verification());
        assertEquals(expectedSimulation, pair.simulation());
        assertNotEquals(pair.verification(), pair.simulation());
        assertEquals(1, deviceSerializations.get());
        assertEquals(before, JsonUtils.toJson(board));
    }

    @Test
    void renamingARequirementDoesNotInvalidateItsVerifiedModel() {
        var spec = new SpecificationDto();
        spec.setId("s1");
        spec.setName("Keep the door locked");
        var condition = new cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto();
        condition.setId("editor-condition-1");
        condition.setSide("a");
        condition.setDeviceId("door");
        condition.setTargetType("state");
        condition.setKey("state");
        condition.setRelation("=");
        condition.setValue("locked");
        spec.setAConditions(List.of(condition));
        var board = snapshot(List.of(spec), List.of());
        String before = fingerprint.modelFingerprint(board);
        spec.setName("Prevent an unexpected unlock");
        condition.setId(null);
        assertEquals(before, fingerprint.modelFingerprint(board));
        condition.setValue("unlocked");
        assertNotEquals(before, fingerprint.modelFingerprint(board));
    }

    @Test
    void specificationsAffectVerificationButDoNotChangeSimulationIdentity() {
        var spec = new SpecificationDto();
        spec.setId("s1");
        var original = snapshot(List.of(spec), List.of());
        var changed = snapshot(List.of(), List.of());
        assertNotEquals(fingerprint.modelFingerprint(original), fingerprint.modelFingerprint(changed));
        assertEquals(fingerprint.simulationFingerprint(original), fingerprint.simulationFingerprint(changed));
    }

    @Test
    void sameCountRuleEditsChangeBothIdentities() {
        var command = new RuleDto.Command("light_1", "on", null, null);
        var rule = new RuleDto();
        rule.setCommand(command);
        var board = snapshot(List.of(), List.of(rule));
        String verification = fingerprint.modelFingerprint(board);
        String simulation = fingerprint.simulationFingerprint(board);
        command.setAction("off");
        assertNotEquals(verification, fingerprint.modelFingerprint(board));
        assertNotEquals(simulation, fingerprint.simulationFingerprint(board));
    }

    @Test
    void geometryAndDisplayNamesAreNotModelChanges() {
        var board = snapshot(List.of(), List.of());
        String identity = fingerprint.modelFingerprint(board);
        board.nodes().get(0).setLabel("A clearer display name");
        var position = new DeviceNodeDto.Position();
        position.setX(300.0);
        position.setY(200.0);
        board.nodes().get(0).setPosition(position);
        board.devices().get(0).setDeviceLabel("A clearer display name");
        assertEquals(identity, fingerprint.modelFingerprint(board));
    }

    @Test
    void presentationFieldNamesRemainSemanticTemplateIdentities() {
        for (String templateName : List.of("deviceLabel", "templateLabel", "formula", "ruleString", "createdAt",
                "Description", "Icon", "description", "icon")) {
            var board = snapshot(List.of(), List.of());
            var manifest = board.templateManifests().get("light");
            manifest.setName(templateName);
            manifest.setInitState("off");
            board.devices().get(0).setTemplateName(templateName);
            var namedBoard = new ModelInputSnapshot(board.nodes(), board.devices(), board.environmentVariables(),
                    board.rules(), board.specifications(), Map.of(templateName, manifest));
            var original = fingerprint.boardFingerprints(namedBoard);
            String originalDomain = fingerprint.paperDomainFingerprint(namedBoard, 5);

            manifest.setDescription("A display-only edit");
            manifest.setIcon("lightbulb");
            assertEquals(original, fingerprint.boardFingerprints(namedBoard), templateName);
            assertEquals(originalDomain, fingerprint.paperDomainFingerprint(namedBoard, 5), templateName);

            manifest.setInitState("on");
            var changed = fingerprint.boardFingerprints(namedBoard);
            assertNotEquals(original.verification(), changed.verification(), templateName);
            assertNotEquals(original.simulation(), changed.simulation(), templateName);
            assertNotEquals(originalDomain, fingerprint.paperDomainFingerprint(namedBoard, 5), templateName);
        }
    }

    private ModelInputSnapshot snapshot(List<SpecificationDto> specs, List<RuleDto> rules) {
        var node = new DeviceNodeDto();
        node.setId("light_1");
        var device = new DeviceVerificationDto();
        device.setVarName("light_1");
        device.setTemplateName("light");
        return new ModelInputSnapshot(List.of(node), List.of(device), List.of(), rules, specs,
                Map.of("light", DeviceManifest.builder().name("light").build()));
    }
}
