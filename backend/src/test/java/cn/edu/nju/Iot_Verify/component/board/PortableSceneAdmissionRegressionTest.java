package cn.edu.nju.Iot_Verify.component.board;

import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortableSceneAdmissionRegressionTest {

    @Test
    void rejectsFalseApiSignalPredicateBeforeConvertingItIntoAnEvent() throws Exception {
        ObjectNode scene = scene();
        ((ObjectNode) scene.at("/rules/0/sources/0")).put("relation", "=").put("value", "FALSE");

        ValidationException failure = assertThrows(ValidationException.class, () -> parse(scene));

        assertTrue(failure.getMessage().contains("API signal conditions"), failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/unexpected", "/rules/0/priority", "/rules/0/sources/0/negated",
            "/templates/0/id", "/specs/0/formula", "/specs/0/aConditions/0/side"})
    void rejectsUnknownFieldsBeforeMappingCanDiscardThem(String pointer) throws Exception {
        ObjectNode scene = scene();
        int separator = pointer.lastIndexOf('/');
        ObjectNode parent = (ObjectNode) scene.at(pointer.substring(0, separator));
        String field = pointer.substring(separator + 1);
        parent.put(field, 99);

        BadRequestException failure = assertThrows(BadRequestException.class, () -> parse(scene));

        assertTrue(failure.getMessage().contains(field), failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/rules/0/name", "/rules/0/sources/0/value",
            "/specs/0/aConditions/0/variableSource"})
    void rejectsWrongOptionalScalarTypesInsteadOfDroppingThem(String pointer) throws Exception {
        ObjectNode scene = scene();
        int separator = pointer.lastIndexOf('/');
        ((ObjectNode) scene.at(pointer.substring(0, separator)))
                .put(pointer.substring(separator + 1), false);

        assertThrows(BadRequestException.class, () -> parse(scene));
    }

    @Test
    void rejectsCoercedDeviceDimensions() throws Exception {
        ObjectNode scene = scene();
        ObjectNode device = (ObjectNode) scene.at("/devices/0");
        device.put("width", "176");
        assertThrows(BadRequestException.class, () -> parse(scene));
        device.put("width", 176.5);
        assertThrows(BadRequestException.class, () -> parse(scene));
    }

    @Test
    void preservesLegalApiSignalsWithoutInventingComparisons() throws Exception {
        BoardBatchDto batch = parse(scene());

        var condition = batch.getRules().get(0).getConditions().get(0);
        assertEquals("api", condition.getTargetType());
        assertEquals("take photo", condition.getAttribute());
        assertNull(condition.getRelation());
        assertNull(condition.getValue());
    }

    @Test
    void directChatMapperSharesTheRawPortableAdmissionGate() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode scene = scene();
        ((ObjectNode) scene.at("/rules/0")).put("priority", 99);
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var batchParser = new BoardBatchRequestParser(
                    mapper, validators.getValidator(), new DeviceTemplateSchemaValidator(mapper));
            var converter = new PortableSceneBatchMapper(mapper, batchParser);

            assertThrows(BadRequestException.class,
                    () -> converter.toBatch(scene, "confirmed-board", "chat_scene_spec_"));
        }
    }

    private ObjectNode scene() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode scene = (ObjectNode) mapper.readTree("""
                {
                  "schema":"iot-verify.board-scene","version":5,"templates":[],
                  "devices":[{
                    "id":"camera_1","templateName":"Camera","label":"Camera",
                    "position":{"x":0,"y":0},"state":"on","width":176,"height":128
                  }],
                  "environmentVariables":[],
                  "specs":[{
                    "templateId":"1","aConditions":[{
                      "deviceId":"camera_1","targetType":"state","key":"state",
                      "relation":"=","value":"on"
                    }],"ifConditions":[],"thenConditions":[]
                  }],
                  "rules":[{
                    "sources":[{
                      "fromId":"camera_1","fromApi":"take photo","itemType":"api"
                    }],
                    "toId":"camera_1","toApi":"off"
                  }]
                }
                """);
        try (var manifest = getClass().getResourceAsStream("/deviceTemplate/Camera.json")) {
            scene.withArray("templates").addObject()
                    .put("name", "Camera")
                    .set("manifest", mapper.readTree(java.util.Objects.requireNonNull(manifest)));
        }
        return scene;
    }

    private BoardBatchDto parse(JsonNode scene) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = mapper.createObjectNode().put("impactToken", "confirmed-board");
        body.set("scene", scene);

        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var batchParser = new BoardBatchRequestParser(
                    mapper, validators.getValidator(), new DeviceTemplateSchemaValidator(mapper));
            var parser = new PortableSceneRequestParser(new PortableSceneBatchMapper(mapper, batchParser));

            return parser.parse(body);
        }
    }
}
