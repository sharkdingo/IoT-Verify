package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrepareToolsToolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiToolManager manager = mock(AiToolManager.class);
    private PrepareToolsTool tool;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<AiToolManager> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(manager);
        when(manager.getAllToolNames()).thenReturn(List.of("prepare_tools", "board_overview", "verify_model"));
        tool = new PrepareToolsTool(mapper, provider);
        UserContextHolder.setUserId(1L);
    }

    @AfterEach void clear() { UserContextHolder.clear(); }

    @Test
    void preparationAcknowledgesExactNamesWithoutExecutingActionsOrDuplicatingSchemas() throws Exception {
        var result = mapper.readTree(tool.execute("{\"names\":[\"verify_model\",\"board_overview\"]}"));
        assertEquals("toolSchemasPrepared", result.path("operation").asText());
        assertEquals(mapper.valueToTree(List.of("verify_model", "board_overview")), result.path("loadedTools"));
        assertEquals(2, result.path("count").asInt());
        assertFalse(result.has("parameters"));
        verify(manager, never()).execute(anyString(), anyString());
        verify(manager, never()).getAllToolDefinitions();
        var definition = tool.getDefinition();
        assertEquals(List.of("names"), definition.parameters().required);
        assertEquals(3, ((java.util.Map<?, ?>) definition.parameters().properties.get("names")).get("maxItems"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"names\":null}", "{\"names\":[]}", "{\"names\":[4]}",
            "{\"names\":[\"unknown\"]}", "{\"names\":[\"verify_model\",\"verify_model\"]}",
            "{\"names\":[\"verify_model\"],\"confirmed\":true}"})
    void malformedOrUnknownSelectionsNeverExecuteDomainActions(String args) throws Exception {
        var result = mapper.readTree(tool.execute(args));
        assertEquals(400, result.path("status").asInt());
        assertEquals("VALIDATION_ERROR", result.path("errorCode").asText());
        verify(manager, never()).execute(anyString(), anyString());
        assertFalse(result.has("loadedTools"));
    }
}
