package cn.edu.nju.Iot_Verify.component.aitool.rule;

import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ListRulesToolTest {

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void listRules_returnsStableIdsBesideLabelsAndSearchesReadableLabels() throws Exception {
        BoardStorageService storage = mock(BoardStorageService.class);
        DeviceNodeDto sensor = node("node-sensor", "Hall sensor");
        DeviceNodeDto light = node("node-light", "Living room light");
        RuleDto rule = RuleDto.builder()
                .id(17L)
                .ruleString("Hall motion lighting")
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("node-sensor")
                        .attribute("motionDetected")
                        .targetType("api")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("node-light")
                        .action("turnOn")
                        .build())
                .build();
        when(storage.getSemanticSnapshot(1L)).thenReturn(new BoardSemanticSnapshotDto(
                List.of(sensor, light), List.of(), List.of(rule), List.of(), List.of()));
        UserContextHolder.setUserId(1L);

        JsonNode response = new ObjectMapper().readTree(
                new ListRulesTool(storage, new ObjectMapper()).execute("{\"keyword\":\"Living room\"}"));

        verify(storage).getSemanticSnapshot(1L);
        verifyNoMoreInteractions(storage);

        assertEquals(1, response.path("count").asInt());
        JsonNode presented = response.path("rules").get(0);
        assertEquals(17L, presented.path("ruleId").asLong());
        assertEquals("node-sensor", presented.path("conditions").get(0).path("deviceId").asText());
        assertEquals("Hall sensor", presented.path("conditions").get(0).path("deviceLabel").asText());
        assertEquals("Living room light", presented.path("command").path("deviceLabel").asText());
        assertTrue(presented.path("conditions").get(0).path("deviceName").isMissingNode());
        assertTrue(presented.path("description").asText().contains("Hall sensor"));
    }

    @Test
    void listRules_rejectsUnknownFilterBeforeReadingBoard() throws Exception {
        BoardStorageService storage = mock(BoardStorageService.class);
        UserContextHolder.setUserId(1L);

        JsonNode response = new ObjectMapper().readTree(
                new ListRulesTool(storage, new ObjectMapper()).execute("{\"query\":\"light\"}"));

        assertEquals("VALIDATION_ERROR", response.path("errorCode").asText());
        verify(storage, never()).getSemanticSnapshot(1L);
    }

    private DeviceNodeDto node(String id, String label) {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId(id);
        node.setLabel(label);
        return node;
    }
}
