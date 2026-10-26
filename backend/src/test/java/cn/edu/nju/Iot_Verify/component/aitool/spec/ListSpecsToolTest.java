package cn.edu.nju.Iot_Verify.component.aitool.spec;

import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ListSpecsToolTest {

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void listSpecs_namesFormulaAsPreviewAndUsesCurrentReadableDeviceLabel() throws Exception {
        BoardStorageService storage = mock(BoardStorageService.class);
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("node-door");
        node.setLabel("Front door");
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec-internal-1");
        spec.setName("Safety requirement");
        spec.setTemplateId("3");
        spec.setTemplateLabel("Door must never be open");
        spec.setFormula("CTLSPEC AG !(node-door.state = open)");
        SpecConditionDto condition = new SpecConditionDto();
        condition.setDeviceId("node-door");
        condition.setDeviceLabel("Old door label");
        condition.setTargetType("state");
        condition.setKey("state");
        condition.setRelation("=");
        condition.setValue("open");
        spec.setAConditions(List.of(condition));
        when(storage.getSemanticSnapshot(1L)).thenReturn(new BoardSemanticSnapshotDto(
                List.of(node), List.of(), List.of(), List.of(spec), List.of()));
        UserContextHolder.setUserId(1L);

        JsonNode response = new ObjectMapper().readTree(
                new ListSpecsTool(storage, new ObjectMapper()).execute("{\"keyword\":\"Front door\"}"));

        assertEquals(1, response.path("count").asInt());
        JsonNode presented = response.path("specs").get(0);
        assertEquals("spec-internal-1", presented.path("specId").asText());
        assertTrue(presented.path("expectedVersion").asText().matches("v1:[0-9a-f]{64}"));
        assertEquals("Front door", presented.path("aConditions").get(0).path("deviceLabel").asText());
        assertEquals("CTL AG NOT (\"Front door\".state = \"open\")",
                presented.path("formulaPreview").asText());
        assertFalse(presented.path("formulaPreview").asText().contains("node-door"));
        assertTrue(presented.path("formula").isMissingNode());
        assertTrue(presented.path("description").asText().contains("Front door"));

        JsonNode nameSearch = new ObjectMapper().readTree(new ListSpecsTool(storage, new ObjectMapper())
                .execute("{\"keyword\":\"safety requirement\"}"));
        assertEquals(1, nameSearch.path("count").asInt());
        assertEquals("Safety requirement", nameSearch.path("specs").get(0).path("name").asText());

        JsonNode versionSearch = new ObjectMapper().readTree(new ListSpecsTool(storage, new ObjectMapper())
                .execute("{\"keyword\":\"" + presented.path("expectedVersion").asText() + "\"}"));
        assertEquals(0, versionSearch.path("count").asInt(), "Version tokens are not searchable specification content");
        JsonNode unmatched = new ObjectMapper().readTree(new ListSpecsTool(storage, new ObjectMapper())
                .execute("{\"keyword\":\"前门\"}"));
        assertEquals(0, unmatched.path("count").asInt());
        assertEquals("No specifications match this literal keyword. Use an empty keyword to list all specifications.",
                unmatched.path("message").asText());
        verify(storage, times(4)).getSemanticSnapshot(1L);
        verifyNoMoreInteractions(storage);
    }

    @Test
    void listSpecs_rejectsNonTextFilterBeforeReadingBoard() throws Exception {
        BoardStorageService storage = mock(BoardStorageService.class);
        UserContextHolder.setUserId(1L);

        JsonNode response = new ObjectMapper().readTree(
                new ListSpecsTool(storage, new ObjectMapper()).execute("{\"keyword\":true}"));

        assertEquals("VALIDATION_ERROR", response.path("errorCode").asText());
        verify(storage, never()).getSemanticSnapshot(1L);
    }
}
