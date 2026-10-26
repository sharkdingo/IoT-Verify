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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortableSceneRequestParserTest {

    @Mock
    private PortableSceneBatchMapper batchMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode body(String sceneFields) throws Exception {
        return objectMapper.readTree("""
                {"impactToken":"board-impact","scene":{%s}}
                """.formatted(sceneFields));
    }

    private static final String SUPPORTED_SCENE = """
            "schema":"iot-verify.board-scene","version":6,
            "templates":[],"devices":[],"environmentVariables":[],"rules":[],"specs":[]
            """;

    @Test
    void parse_forwardsTheSceneAndTokenToTheSharedMapper() throws Exception {
        BoardBatchDto expected = new BoardBatchDto();
        when(batchMapper.toBatch(any(), eq("board-impact"), any())).thenReturn(expected);
        PortableSceneRequestParser parser = new PortableSceneRequestParser(batchMapper);

        assertEquals(expected, parser.parse(body(SUPPORTED_SCENE)));

        ArgumentCaptor<JsonNode> sceneCaptor = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<String> prefixCaptor = ArgumentCaptor.forClass(String.class);
        verify(batchMapper).toBatch(sceneCaptor.capture(), eq("board-impact"), prefixCaptor.capture());
        // The token is not smuggled into the scene: an exported file must upload unmodified.
        assertTrue(sceneCaptor.getValue().path("impactToken").isMissingNode());
        assertEquals("iot-verify.board-scene", sceneCaptor.getValue().path("schema").asText());
        assertEquals("scene_spec_", prefixCaptor.getValue());
    }

    /**
     * The failure this endpoint's version check exists to prevent: a producer bumped to 5 against an
     * admitting validator still demanding 4 rejected every scene. The message names both sides so a
     * user can act on it by re-exporting.
     */
    @Test
    void parse_rejectsAnUnsupportedVersionAndNamesBothSides() throws Exception {
        PortableSceneRequestParser parser = new PortableSceneRequestParser(batchMapper);

        BadRequestException failure = assertThrows(BadRequestException.class, () -> parser.parse(body("""
                "schema":"iot-verify.board-scene","version":4,
                "templates":[],"devices":[],"environmentVariables":[],"rules":[],"specs":[]
                """)));

        assertTrue(failure.getMessage().contains("version 6"), failure.getMessage());
        assertTrue(failure.getMessage().contains("version 4"), failure.getMessage());
        assertTrue(failure.getMessage().contains("No board data was changed"), failure.getMessage());
        verifyNoInteractions(batchMapper);
    }

    @Test
    void parse_rejectsAForeignSchema() throws Exception {
        PortableSceneRequestParser parser = new PortableSceneRequestParser(batchMapper);

        BadRequestException failure = assertThrows(BadRequestException.class, () -> parser.parse(body("""
                "schema":"some.other.tool","version":5,
                "templates":[],"devices":[],"environmentVariables":[],"rules":[],"specs":[]
                """)));

        assertTrue(failure.getMessage().contains("some.other.tool"), failure.getMessage());
        verifyNoInteractions(batchMapper);
    }

    /** A misplaced field is a caller that misread the contract; importing it would lose data. */
    @Test
    void parse_rejectsUnknownTopLevelFields() throws Exception {
        PortableSceneRequestParser parser = new PortableSceneRequestParser(batchMapper);
        JsonNode withStrayCollection = objectMapper.readTree("""
                {"impactToken":"board-impact","nodes":[],"scene":{%s}}
                """.formatted(SUPPORTED_SCENE));

        BadRequestException failure = assertThrows(BadRequestException.class,
                () -> parser.parse(withStrayCollection));

        assertTrue(failure.getMessage().contains("nodes"), failure.getMessage());
        verifyNoInteractions(batchMapper);
    }

    @Test
    void parse_rejectsAMissingSceneObject() throws Exception {
        PortableSceneRequestParser parser = new PortableSceneRequestParser(batchMapper);
        JsonNode tokenOnly = objectMapper.readTree("{\"impactToken\":\"board-impact\"}");

        BadRequestException failure = assertThrows(BadRequestException.class,
                () -> parser.parse(tokenOnly));

        assertTrue(failure.getMessage().contains("'scene'"), failure.getMessage());
        verifyNoInteractions(batchMapper);
    }
}
