package cn.edu.nju.Iot_Verify.component.aitool.node;

import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentVariableChangeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceCreationResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceRuntimeConfigDto;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.NodeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for AddNodeTool numeric parameter parsing.
 * Ensures null JSON values and absent fields result in null defaults, while
 * string or fractional integer fields fail validation before reaching NodeService.
 */
@ExtendWith(MockitoExtension.class)
class AddNodeToolNullParamsTest {

    @Mock
    private NodeService nodeService;

    private ObjectMapper objectMapper;
    private AddNodeTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tool = new AddNodeTool(nodeService, objectMapper);
        UserContextHolder.setUserId(1L);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void definition_directsModelToAuthoritativeTemplateCatalog() {
        @SuppressWarnings("unchecked")
        Map<String, Object> templateProperty = (Map<String, Object>)
                tool.getDefinition().parameters().properties.get("templateName");
        String propertyDescription = String.valueOf(templateProperty.get("description"));

        assertTrue(propertyDescription.contains("list_templates"));
        assertFalse(propertyDescription.contains("board_overview"));
        assertTrue(tool.getDefinition().description().contains("targeted mutation"));
    }

    @Test
    void execute_withNullJsonValues_passesNullToService() throws Exception {
        when(nodeService.addNode(eq(1L), eq("Air Conditioner"), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(creation("ac_1"));

        String result = tool.execute("{\"templateName\":\"Air Conditioner\",\"x\":null,\"y\":null,\"w\":null,\"h\":null}");

        verify(nodeService).addNode(eq(1L), eq("Air Conditioner"), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull());
        assertTrue(result.contains("ac_1"));
    }

    @Test
    void execute_withNonNumericStrings_returnsValidationError() throws Exception {
        String result = tool.execute("{\"templateName\":\"Air Conditioner\",\"x\":\"abc\",\"y\":\"def\",\"w\":\"xyz\",\"h\":\"!\"}");

        verifyNoInteractions(nodeService);
        assertTrue(result.contains("VALIDATION_ERROR"));
        assertTrue(result.contains("must be a JSON number"));
    }

    @Test
    void execute_withAbsentFields_passesNullToService() throws Exception {
        when(nodeService.addNode(eq(1L), eq("Air Conditioner"), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(creation("ac_3"));

        String result = tool.execute("{\"templateName\":\"Air Conditioner\"}");

        verify(nodeService).addNode(eq(1L), eq("Air Conditioner"), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull());
        assertTrue(result.contains("ac_3"));
    }

    @Test
    void execute_returnsItemizedEnvironmentPoolEffects() throws Exception {
        BoardEnvironmentVariableDto temperature =
                new BoardEnvironmentVariableDto("temperature", "20", "trusted", "public");
        EnvironmentVariableChangeDto added = EnvironmentVariableChangeDto.builder()
                .changeType(EnvironmentVariableChangeDto.ChangeType.ADDED)
                .name("temperature")
                .currentValue(temperature)
                .build();
        DeviceCreationResultDto creation = creation("sensor_1");
        creation.setEnvironmentVariables(java.util.List.of(temperature));
        creation.setEnvironmentChanges(java.util.List.of(added));
        when(nodeService.addNode(eq(1L), eq("Air Conditioner"), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(creation);

        JsonNode result = objectMapper.readTree(tool.execute("{\"templateName\":\"Air Conditioner\"}"));

        assertEquals("temperature", result.path("environmentChanges").get(0).path("name").asText());
        assertEquals("ADDED", result.path("environmentChanges").get(0).path("changeType").asText());
        assertEquals("20", result.path("environmentVariables").get(0).path("value").asText());
    }

    @Test
    void execute_withValidNumbers_passesValuesToService() throws Exception {
        when(nodeService.addNode(eq(1L), eq("Air Conditioner"), isNull(),
                eq(100.5), eq(200.0), isNull(), eq(120), eq(80)))
                .thenReturn(creation("ac_4"));

        String result = tool.execute("{\"templateName\":\"Air Conditioner\",\"x\":100.5,\"y\":200,\"w\":120,\"h\":80}");

        verify(nodeService).addNode(eq(1L), eq("Air Conditioner"), isNull(),
                eq(100.5), eq(200.0), isNull(), eq(120), eq(80));
        assertTrue(result.contains("ac_4"));
    }

    @Test
    void execute_withEmptyStringValues_returnsValidationError() throws Exception {
        String result = tool.execute("{\"templateName\":\"Air Conditioner\",\"x\":\"\",\"y\":\"\"}");

        verifyNoInteractions(nodeService);
        assertTrue(result.contains("VALIDATION_ERROR"));
        assertTrue(result.contains("must be a JSON number"));
    }

    @Test
    void execute_withNumericStrings_returnsValidationError() throws Exception {
        String result = tool.execute("{\"templateName\":\"Air Conditioner\",\"x\":\"150\",\"y\":\"250\",\"w\":\"176\",\"h\":\"128\"}");

        verifyNoInteractions(nodeService);
        assertTrue(result.contains("VALIDATION_ERROR"));
        assertTrue(result.contains("must be a JSON number"));
    }

    @Test
    void execute_withFractionalWidth_returnsValidationError() {
        String result = tool.execute("{\"templateName\":\"Air Conditioner\",\"w\":176.5}");

        verifyNoInteractions(nodeService);
        assertTrue(result.contains("VALIDATION_ERROR"));
        assertTrue(result.contains("32-bit JSON integer"));
    }

    @Test
    void execute_preservesExplicitRuntimeConfigurationForNodeService() throws Exception {
        when(nodeService.addNode(eq(1L), eq("Air Conditioner"), eq("bedroom_ac"),
                isNull(), isNull(), any(DeviceRuntimeConfigDto.class), isNull(), isNull()))
                .thenReturn(creation("bedroom_ac"));

        String result = tool.execute("""
                {
                  "templateName":"Air Conditioner",
                  "label":"bedroom_ac",
                  "state":"cool",
                  "currentStateTrust":"UNTRUSTED",
                  "currentStatePrivacy":"PRIVATE",
                  "variables":[
                    {"name":"fanSpeed","value":"3","trust":"trusted"},
                    {"name":"sensorOffset","value":"0","trust":null}
                  ],
                  "privacies":[{"name":"fanSpeed","privacy":"private"}]
                }
                """);

        assertEquals("created", objectMapper.readTree(result).path("operation").asText(), result);
        ArgumentCaptor<DeviceRuntimeConfigDto> runtimeCaptor = ArgumentCaptor.forClass(DeviceRuntimeConfigDto.class);
        verify(nodeService).addNode(eq(1L), eq("Air Conditioner"), eq("bedroom_ac"),
                isNull(), isNull(), runtimeCaptor.capture(), isNull(), isNull());
        DeviceRuntimeConfigDto runtime = runtimeCaptor.getValue();
        assertEquals("cool", runtime.getState());
        assertEquals("untrusted", runtime.getCurrentStateTrust());
        assertEquals("private", runtime.getCurrentStatePrivacy());
        assertEquals("fanSpeed", runtime.getVariables().get(0).getName());
        assertEquals("trusted", runtime.getVariables().get(0).getTrust());
        assertEquals("sensorOffset", runtime.getVariables().get(1).getName());
        assertNull(runtime.getVariables().get(1).getTrust());
        assertEquals("private", runtime.getPrivacies().get(0).getPrivacy());
        assertTrue(result.contains("bedroom_ac"));
    }

    @Test
    void execute_rejectsNonStringSemanticFieldsAndUnknownFieldsBeforeMutation() throws Exception {
        JsonNode numericTemplate = objectMapper.readTree(tool.execute("{\"templateName\":42}"));
        JsonNode numericValue = objectMapper.readTree(tool.execute("""
                {
                  "templateName":"Air Conditioner",
                  "variables":[{"name":"fanSpeed","value":3}]
                }
                """));
        JsonNode unknownField = objectMapper.readTree(tool.execute(
                "{\"templateName\":\"Air Conditioner\",\"deviceName\":\"Bedroom AC\"}"));

        assertEquals("VALIDATION_ERROR", numericTemplate.path("errorCode").asText());
        assertTrue(numericTemplate.path("error").asText().contains("non-empty string"));
        assertEquals("VALIDATION_ERROR", numericValue.path("errorCode").asText());
        assertTrue(numericValue.path("error").asText().contains("must be a non-empty string"));
        assertEquals("VALIDATION_ERROR", unknownField.path("errorCode").asText());
        assertTrue(unknownField.path("error").asText().contains("deviceName"));
        verifyNoInteractions(nodeService);
    }

    private DeviceCreationResultDto creation(String id) {
        DeviceNodeDto device = new DeviceNodeDto();
        device.setId(id);
        device.setLabel(id);
        device.setTemplateName("Air Conditioner");
        DeviceNodeDto.Position position = new DeviceNodeDto.Position();
        position.setX(250.0);
        position.setY(250.0);
        device.setPosition(position);
        device.setState("Working");
        device.setWidth(176);
        device.setHeight(128);
        return DeviceCreationResultDto.builder()
                .device(device)
                .initialStateSource("templateDefault")
                .build();
    }
}
