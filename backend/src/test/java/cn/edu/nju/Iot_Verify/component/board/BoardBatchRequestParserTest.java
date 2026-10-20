package cn.edu.nju.Iot_Verify.component.board;

import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoardBatchRequestParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private BoardBatchRequestParser parser;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        parser = new BoardBatchRequestParser(
                objectMapper,
                validator,
                new DeviceTemplateSchemaValidator(objectMapper));
    }

    @Test
    void unknownNestedField_isRejectedInsteadOfSilentlyDiscarded() throws Exception {
        JsonNode body = objectMapper.readTree("""
                {
                  "nodes": [{
                    "id": "device_1",
                    "templateName": "Lamp",
                    "label": "Hall lamp",
                    "position": {"x": 10, "y": 20},
                    "width": 176,
                    "height": 128,
                    "futureBehavior": "must-not-disappear"
                  }]
                }
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () -> parser.parse(body));

        assertThat(exception.getMessage())
                .contains("futureBehavior")
                .contains("nodes[0].futureBehavior")
                .contains("no board data was changed");
    }

    @Test
    void unknownManifestField_isValidatedBeforeTypedDtoCanDropIt() throws Exception {
        JsonNode body = objectMapper.readTree("""
                {
                  "templateSnapshots": [{
                    "name": "Lamp",
                    "manifest": {"Name": "Lamp", "UnknownBehavior": true}
                  }]
                }
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () -> parser.parse(body));

        assertThat(exception.getMessage())
                .contains("backend/device-template-schema.json")
                .contains("UnknownBehavior");
        assertThat(exception.getErrors()).containsKey("templateSnapshots[0].manifest.UnknownBehavior");
    }

    @Test
    void responseAndCatalogFields_areNotAcceptedAsPortableSceneInput() throws Exception {
        BadRequestException responseField = assertThrows(BadRequestException.class, () -> parser.parse(
                objectMapper.readTree("{\"createdTemplates\": []}")));
        assertThat(responseField.getMessage()).contains("response-only");

        BadRequestException catalogField = assertThrows(BadRequestException.class, () -> parser.parse(
                objectMapper.readTree("""
                        {
                          "templateSnapshots": [{
                            "id": 3,
                            "name": "Lamp",
                            "manifest": {"Name": "Lamp"}
                          }]
                        }
                        """)));
        assertThat(catalogField.getMessage()).contains("templateSnapshots[0].id").contains("not portable");
    }

    @Test
    void beanValidationStillRunsAfterStrictParsing() throws Exception {
        JsonNode body = objectMapper.readTree("""
                {
                  "nodes": [{
                    "templateName": "Lamp",
                    "label": "Hall lamp",
                    "position": {"x": 10, "y": 20},
                    "width": 176,
                    "height": 128
                  }]
                }
                """);

        ValidationException exception = assertThrows(ValidationException.class, () -> parser.parse(body));

        assertThat(exception.getErrors()).containsKey("nodes[0].id");
    }

    /**
     * This parser is the scene-import boundary, and it deserializes strictly: a field missing from the DTO
     * is rejected outright rather than dropped. So the DTO carrying `variableSource` is what makes an
     * exported scene re-importable at all — without it every bundled scene fails with
     * "Unknown field 'variableSource'" and no board data changes.
     */
    @Test
    void variableSource_isAcceptedByStrictSceneParsing() throws Exception {
        JsonNode body = objectMapper.readTree("""
                {
                  "impactToken": "token-1",
                  "nodes": [],
                  "environmentVariables": [],
                  "rules": [],
                  "templateSnapshots": [],
                  "specs": [{
                    "id": "spec-1",
                    "templateId": "1",
                    "aConditions": [{
                      "side": "a",
                      "deviceId": "sensor_1",
                      "targetType": "variable",
                      "key": "temperature",
                      "variableSource": "environment",
                      "relation": ">",
                      "value": "30"
                    }],
                    "ifConditions": [],
                    "thenConditions": []
                  }]
                }
                """);

        BoardBatchDto batch = parser.parse(body);

        assertThat(batch.getSpecs().get(0).getAConditions().get(0).getVariableSource())
                .isEqualTo("environment");
    }

    @Test
    void variableSource_toleratesSurroundingWhitespaceLikeRelationDoes() throws Exception {
        // The service layer trims before comparing, so a padded value is one the semantic gates accept and
        // canonicalize. Anchoring the DTO pattern without \s* rejected it here first, with a generic message
        // that hid the actionable one explaining why there is no default.
        JsonNode body = objectMapper.readTree("""
                {
                  "impactToken": "token-1",
                  "nodes": [],
                  "environmentVariables": [],
                  "rules": [],
                  "templateSnapshots": [],
                  "specs": [{
                    "id": "spec-1",
                    "templateId": "1",
                    "aConditions": [{
                      "side": "a",
                      "deviceId": "sensor_1",
                      "targetType": "variable",
                      "key": "temperature",
                      "variableSource": " environment ",
                      "relation": " > ",
                      "value": "30"
                    }],
                    "ifConditions": [],
                    "thenConditions": []
                  }]
                }
                """);

        assertThat(parser.parse(body).getSpecs()).hasSize(1);
    }

    @Test
    void completeEmptyCollectionsRemainAValidReplacementRequest() throws Exception {
        BoardBatchDto batch = parser.parse(objectMapper.readTree("""
                {
                  "impactToken": "confirmed-current-board",
                  "templateSnapshots": [],
                  "nodes": [],
                  "environmentVariables": [],
                  "rules": [],
                  "specs": []
                }
                """));

        assertThat(batch.getNodes()).isEmpty();
        assertThat(batch.getEnvironmentVariables()).isEmpty();
        assertThat(batch.getRules()).isEmpty();
        assertThat(batch.getSpecs()).isEmpty();
        assertThat(batch.getTemplateSnapshots()).isEmpty();
        assertThat(batch.getImpactToken()).isEqualTo("confirmed-current-board");
    }

    @Test
    void partialOrUnconfirmedReplacement_isRejectedBeforeServiceInvocation() throws Exception {
        ValidationException exception = assertThrows(ValidationException.class, () -> parser.parse(
                objectMapper.readTree("""
                        {
                          "nodes": [],
                          "rules": [],
                          "specs": []
                        }
                        """)));

        assertThat(exception.getErrors()).containsKeys(
                "environmentVariables", "templateSnapshots", "impactToken");
    }
}
