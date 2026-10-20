package cn.edu.nju.Iot_Verify.component.board;

import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.controller.BoardStorageController;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.GlobalExceptionHandler;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.ModelFingerprint;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SceneImportDiagnosticsRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private PortableSceneRequestParser parser;
    private BoardBatchRequestParser batchParser;
    private DeviceTemplateSchemaValidator schemaValidator;

    @BeforeEach
    void setUp() {
        schemaValidator = new DeviceTemplateSchemaValidator(mapper);
        batchParser = new BoardBatchRequestParser(mapper,
                Validation.buildDefaultValidatorFactory().getValidator(), schemaValidator);
        parser = new PortableSceneRequestParser(new PortableSceneBatchMapper(mapper, batchParser));
    }

    @Test
    void missingSecondRuleTargetNamesItsPortablePositionAndField() throws Exception {
        ObjectNode body = body();
        body.withObject("scene").set("rules", mapper.readTree("""
                [{"sources":[],"toId":"lamp","toApi":"on"},
                 {"sources":[],"toApi":"off"}]
                """));
        BadRequestException error = assertThrows(BadRequestException.class, () -> parser.parse(body));
        assertThat(error.getMessage()).contains("rules[1].toId");
        assertThat(badRequest(error).path("data").path("errors").has("rules[1].toId")).isTrue();
    }

    @Test
    void missingNestedSourceAttributeNamesBothIndexes() throws Exception {
        ObjectNode body = body();
        body.withObject("scene").set("rules", mapper.readTree("""
                [{"sources":[{"fromId":"sensor","fromApi":"event","itemType":"api"},
                            {"fromId":"sensor","itemType":"api"}],"toId":"lamp","toApi":"on"}]
                """));
        BadRequestException error = assertThrows(BadRequestException.class, () -> parser.parse(body));
        assertThat(badRequest(error).path("data").path("errors").has("rules[0].sources[1].fromApi")).isTrue();
    }

    @Test
    void missingSecondSpecificationSideNamesItsCollectionPosition() throws Exception {
        ObjectNode body = body();
        body.withObject("scene").set("specs", mapper.readTree("""
                [{"templateId":"1","aConditions":[],"ifConditions":[],"thenConditions":[]},
                 {"templateId":"1","aConditions":[],"ifConditions":[]}]
                """));
        BadRequestException error = assertThrows(BadRequestException.class, () -> parser.parse(body));
        assertThat(badRequest(error).path("data").path("errors").has("specs[1].thenConditions")).isTrue();
    }

    @Test
    void unknownPortableFieldKeepsItsReasonAndCoordinateInTheEnvelope() throws Exception {
        ObjectNode body = body();
        body.withObject("scene").set("rules", mapper.readTree("[{\"priority\":99}]"));
        BadRequestException error = assertThrows(BadRequestException.class, () -> parser.parse(body));
        JsonNode response = badRequest(error);
        assertThat(response.path("code").asInt()).isEqualTo(400);
        assertThat(response.path("data").path("errors").path("rules[0].priority").asText())
                .contains("Unknown field");
    }

    @Test
    void apiShapeBeanValidationPointsToAuthoredSourceRatherThanSyntheticGetter() throws Exception {
        ObjectNode body = body();
        body.withObject("scene").set("rules", mapper.readTree("""
                [{"sources":[{"fromId":"sensor","fromApi":"event","itemType":"api",
                              "relation":"=","value":"FALSE"}],"toId":"lamp","toApi":"on"}]
                """));
        ValidationException error = assertThrows(ValidationException.class, () -> parser.parse(body));
        assertThat(error.getErrors()).containsKey("rules[0].sources[0]");
        assertThat(error.getErrors().get("rules[0].sources[0]")).contains("relation or value");
        assertThat(error.getErrors().keySet()).noneMatch(path -> path.contains("conditions") || path.contains("ShapeValid"));
    }

    @Test
    void semanticRejectionIsMappedOnlyAtThePortableControllerBoundary() throws Exception {
        BoardStorageService service = mock(BoardStorageService.class);
        when(service.saveBoardBatch(eq(7L), any())).thenThrow(new ValidationException(Map.of(
                "nodes[0].id", "Remove leading whitespace",
                "rules[1].conditions[2].attribute", "State rule conditions must use attribute 'state'",
                "rules[1].conditions[2].deviceName", "Unknown condition device",
                "rules[1].conditions[2].targetType", "Select a supported condition kind",
                "rules[1].command.deviceName", "Unknown command device",
                "rules[1].command.action", "Select an API declared by the target template")));
        BoardStorageController controller = new BoardStorageController(service, new BoardDataConverter(service),
                new ModelFingerprint(mapper), null, null, null, null, null, mapper,
                schemaValidator, batchParser, parser, null);
        ValidationException error = assertThrows(ValidationException.class, () -> controller.importScene(7L, body()));
        assertThat(error.getErrors()).containsKeys("devices[0].id", "rules[1].sources[2].fromApi",
                "rules[1].sources[2].fromId", "rules[1].sources[2].itemType", "rules[1].toId", "rules[1].toApi");
    }

    @Test
    void directBatchKeepsItsInternalCoordinates() throws Exception {
        JsonNode body = mapper.readTree("""
                {"nodes":[{"templateName":"Lamp","label":"Hall lamp","position":{"x":0,"y":0}}],
                 "environmentVariables":[],"rules":[],"specs":[],"templateSnapshots":[],"impactToken":"reviewed"}
                """
        );
        ValidationException error = assertThrows(ValidationException.class, () -> batchParser.parse(body));
        assertThat(error.getErrors()).containsKey("nodes[0].id").doesNotContainKey("devices[0].id");
    }

    @Test
    void missingTemplateManifestNamesThePortableTemplateIndex() throws Exception {
        ObjectNode body = body();
        body.withObject("scene").set("templates", mapper.readTree("[{\"name\":\"Lamp\"}]"));
        BadRequestException error = assertThrows(BadRequestException.class, () -> parser.parse(body));
        assertThat(badRequest(error).path("data").path("errors").has("templates[0].manifest")).isTrue();
    }

    @Test
    void manifestSchemaErrorsIdentifyTheNestedPortableField() throws Exception {
        ObjectNode body = body();
        ObjectNode manifest;
        try (var input = getClass().getResourceAsStream("/deviceTemplate/Light.json")) {
            manifest = (ObjectNode) mapper.readTree(input);
        }
        ((ObjectNode) manifest.at("/WorkingStates/0")).put("Trust", "unexpected");
        ObjectNode template = mapper.createObjectNode().put("name", "Light").set("manifest", manifest);
        body.withObject("scene").withArray("templates").add(template);
        BadRequestException error = assertThrows(BadRequestException.class, () -> parser.parse(body));
        assertThat(badRequest(error).path("data").path("errors").has("templates[0].manifest.WorkingStates[0].Trust")).isTrue();
    }

    @Test
    void missingManifestPropertyNamesTheRequiredField() throws Exception {
        ObjectNode body = body();
        ObjectNode manifest;
        try (var input = getClass().getResourceAsStream("/deviceTemplate/Light.json")) {
            manifest = (ObjectNode) mapper.readTree(input);
        }
        manifest.remove("Name");
        body.withObject("scene").withArray("templates")
                .add(mapper.createObjectNode().put("name", "Light").set("manifest", manifest));
        BadRequestException error = assertThrows(BadRequestException.class, () -> parser.parse(body));
        assertThat(badRequest(error).path("data").path("errors").has("templates[0].manifest.Name")).isTrue();
    }

    @Test
    void templateRequestTypeErrorIdentifiesTheAuthoredField() throws Exception {
        JsonNode manifest;
        try (var input = getClass().getResourceAsStream("/deviceTemplate/Light.json")) {
            manifest = mapper.readTree(input);
        }
        ObjectNode request = mapper.createObjectNode().put("id", "not-an-integer")
                .put("name", "Light").set("manifest", manifest);
        BoardStorageService service = mock(BoardStorageService.class);
        BoardStorageController controller = new BoardStorageController(service, new BoardDataConverter(service),
                new ModelFingerprint(mapper), null, null, null, null, null, mapper,
                schemaValidator, batchParser, parser, null);
        BadRequestException error = assertThrows(BadRequestException.class, () -> controller.addTemplate(7L, request));
        assertThat(badRequest(error).path("data").path("errors").has("id")).isTrue();
        assertThat(error.getMessage()).contains("'id'").doesNotContain("com.fasterxml");
    }

    private ObjectNode body() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"impactToken":"reviewed","scene":{"schema":"iot-verify.board-scene","version":5,
                 "templates":[],"devices":[],"environmentVariables":[],"rules":[],"specs":[]}}
                """
        );
    }

    private JsonNode badRequest(BadRequestException error) {
        return mapper.valueToTree(handler.handleBadRequestException(error).getBody());
    }
}
