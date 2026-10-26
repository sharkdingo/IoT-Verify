package cn.edu.nju.Iot_Verify.component.board;

import cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortableSceneBatchMapperTest {

    @Mock
    private BoardBatchRequestParser parser;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode scene(String specConditions) throws Exception {
        return objectMapper.readTree("""
                {
                  "schema": "iot-verify.board-scene",
                  "version": 6,
                  "templates": [],
                  "devices": [{
                    "id":"device_1","templateName":"Light","label":"Hall Light",
                    "position":{"x":0,"y":0},"state":"off","width":176,"height":128
                  }],
                  "environmentVariables": [],
                  "rules": [{
                    "name":"Turn on the light",
                    "sources":[{
                      "fromId":"device_1","itemType":"state","fromApi":"state",
                      "relation":"=","value":"off"
                    }],
                    "toId":"device_1","toApi":"on"
                  }],
                  "specs": [{
                    "name": "Safety requirement", "templateId":"1",
                    "aConditions":[%s],
                    "ifConditions":[],"thenConditions":[]
                  }]
                }
                """.formatted(specConditions));
    }

    private JsonNode capturedBody(JsonNode scene, String specIdPrefix) {
        BoardBatchDto expected = new BoardBatchDto();
        when(parser.parse(any())).thenReturn(expected);
        PortableSceneBatchMapper mapper = new PortableSceneBatchMapper(objectMapper, parser);

        assertEquals(expected, mapper.toBatch(scene, "board-impact", specIdPrefix));

        ArgumentCaptor<JsonNode> bodyCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(parser).parse(bodyCaptor.capture());
        return bodyCaptor.getValue();
    }

    @Test
    void toBatch_convertsPortableRulesAndSpecificationsToBoardWriteContract() throws Exception {
        JsonNode body = capturedBody(scene("""
                {"deviceId":"device_1","targetType":"api","key":"on","relation":"=","value":"TRUE"}
                """), "scene_spec_");

        assertEquals("board-impact", body.path("impactToken").asText());
        assertEquals("device_1", body.path("nodes").get(0).path("id").asText());
        JsonNode condition = body.path("rules").get(0).path("conditions").get(0);
        assertEquals("device_1", condition.path("deviceName").asText());
        assertEquals("state", condition.path("attribute").asText());
        assertEquals("Turn on the light", body.path("rules").get(0).path("ruleString").asText());
        JsonNode spec = body.path("specs").get(0);
        assertEquals("scene_spec_1", spec.path("id").asText());
        assertFalse(spec.path("aConditions").get(0).has("side"));
        assertEquals("api", spec.path("aConditions").get(0).path("targetType").asText());
        assertEquals("=", spec.path("aConditions").get(0).path("relation").asText());
        assertEquals("TRUE", spec.path("aConditions").get(0).path("value").asText());
    }

    @Test
    void toBatch_marksSpecificationProvenanceFromTheCallersPrefix() throws Exception {
        JsonNode body = capturedBody(scene("""
                {"deviceId":"device_1","targetType":"api","key":"on","relation":"=","value":"TRUE"}
                """), "chat_scene_spec_");

        assertEquals("chat_scene_spec_1", body.path("specs").get(0).path("id").asText());
    }

    /**
     * The field whose loss produced three separate user-visible failures, one per hand-written copy of
     * this mapping. It is asserted here because this is now the only copy.
     */
    @Test
    void toBatch_carriesVariableSourceThroughToAdmission() throws Exception {
        JsonNode body = capturedBody(scene("""
                {"deviceId":"device_1","targetType":"variable","key":"motion",
                 "variableSource":"reported","relation":"=","value":"detected"}
                """), "scene_spec_");

        JsonNode condition = body.path("specs").get(0).path("aConditions").get(0);
        assertEquals("reported", condition.path("variableSource").asText());
    }

    /**
     * An {@code api} source is a signal event, and {@code RuleDto.isApiSignalShapeValid} rejects one
     * that carries a relation or value. Emitting them as blanks would fail admission on a shape the
     * portable file never expressed.
     */
    @Test
    void toBatch_omitsRelationAndValueForApiSignalSources() throws Exception {
        JsonNode body = capturedBody(objectMapper.readTree("""
                {
                  "templates": [], "devices": [], "environmentVariables": [], "specs": [],
                  "rules": [{
                    "sources":[{"fromId":"camera_1","itemType":"api","fromApi":"take photo"}],
                    "toId":"alarm_1","toApi":"siren"
                  }]
                }
                """), "scene_spec_");

        JsonNode condition = body.path("rules").get(0).path("conditions").get(0);
        assertEquals("api", condition.path("targetType").asText());
        assertEquals("take photo", condition.path("attribute").asText());
        assertFalse(condition.has("relation"));
        assertFalse(condition.has("value"));
    }

    /** A missing collection is rejected, never read as an empty one that would silently erase a board. */
    @Test
    void toBatch_rejectsAMissingCollectionRatherThanTreatingItAsEmpty() throws Exception {
        PortableSceneBatchMapper mapper = new PortableSceneBatchMapper(objectMapper, parser);
        JsonNode noRules = objectMapper.readTree(
                "{\"templates\":[],\"devices\":[],\"environmentVariables\":[],\"specs\":[]}");

        BadRequestException failure = assertThrows(BadRequestException.class,
                () -> mapper.toBatch(noRules, "board-impact", "scene_spec_"));
        assertTrue(failure.getMessage().contains("rules"), failure.getMessage());
        assertTrue(failure.getMessage().contains("no board data was changed"), failure.getMessage());
    }

    @Test
    void toBatch_rejectsABlankImpactToken() throws Exception {
        PortableSceneBatchMapper mapper = new PortableSceneBatchMapper(objectMapper, parser);
        JsonNode empty = objectMapper.readTree(
                "{\"templates\":[],\"devices\":[],\"environmentVariables\":[],\"rules\":[],\"specs\":[]}");

        BadRequestException failure = assertThrows(BadRequestException.class,
                () -> mapper.toBatch(empty, "   ", "scene_spec_"));
        assertTrue(failure.getMessage().contains("impactToken"), failure.getMessage());
    }
}
