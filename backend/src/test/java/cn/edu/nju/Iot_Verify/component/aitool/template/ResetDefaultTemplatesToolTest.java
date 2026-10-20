package cn.edu.nju.Iot_Verify.component.aitool.template;

import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentVariableChangeDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateAffectedDeviceDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateResetChangeDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateResetResultDto;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResetDefaultTemplatesToolTest {

    @Mock
    private BoardStorageService boardStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ResetDefaultTemplatesTool tool;

    @BeforeEach
    void setUp() {
        tool = new ResetDefaultTemplatesTool(
                boardStorageService,
                new AiDestructiveActionGuard(objectMapper),
                objectMapper);
        UserContextHolder.setUserId(1L);
        UserContextHolder.setChatSessionId("session-1");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void execute_previewsAndAppliesOnlyAfterDestructiveConfirmation() throws Exception {
        DefaultTemplateResetResultDto preview = preview(true, "domain-token");
        when(boardStorageService.previewDefaultTemplateReset(1L)).thenReturn(preview);

        JsonNode first = objectMapper.readTree(tool.execute("{\"confirmed\":false}"));

        assertTrue(first.path("requiresUserConfirmation").asBoolean());
        assertEquals(3, first.path("preview").path("editHistoryEntryCount").asInt());
        JsonNode environmentChange = first.path("preview").path("environmentChanges").get(0);
        assertEquals("temperature", environmentChange.path("name").asText());
        assertEquals("UPDATED", environmentChange.path("changeType").asText());
        assertEquals("private", environmentChange.path("previousValue").path("privacy").asText());
        assertEquals("public", environmentChange.path("currentValue").path("privacy").asText());
        String publicToken = first.path("preview").path("impactToken").asText();
        verify(boardStorageService, never()).resetDefaultTemplates(1L, "domain-token");

        UserContextHolder.setConfirmedProtectedAction("DEFAULT_TEMPLATE_RESET", publicToken);
        when(boardStorageService.resetDefaultTemplates(1L, "domain-token"))
                .thenReturn(preview(true, "domain-token"));
        JsonNode applied = objectMapper.readTree(tool.execute(
                "{\"confirmed\":true,\"impactToken\":\"" + publicToken + "\"}"));

        assertEquals("reset", applied.path("operation").asText());
        assertEquals("NOT_VERIFIED", applied.path("verificationStatus").asText());
        verify(boardStorageService).resetDefaultTemplates(1L, "domain-token");
    }

    @Test
    void execute_blockedPreviewNeverRequestsConfirmation() throws Exception {
        when(boardStorageService.previewDefaultTemplateReset(1L)).thenReturn(preview(false, "blocked"));

        JsonNode result = objectMapper.readTree(tool.execute("{\"confirmed\":false}"));

        assertEquals(false, result.path("requiresUserConfirmation").asBoolean());
        assertEquals("BOARD_MODEL_INCOMPATIBLE",
                result.path("preview").path("blockers").get(0).path("reasonCode").asText());
        verify(boardStorageService, never()).resetDefaultTemplates(eq(1L), anyString());
    }

    private DefaultTemplateResetResultDto preview(boolean canApply, String token) {
        return DefaultTemplateResetResultDto.builder()
                .operation("preview")
                .impactToken(token)
                .canApply(canApply)
                .editHistoryEntryCount(3)
                .templateChanges(List.of(DefaultTemplateResetChangeDto.builder()
                        .templateName("Air Conditioner")
                        .changeType(DefaultTemplateResetChangeDto.ChangeType.REFRESH_DEFAULT)
                        .semanticsChanged(true)
                        .build()))
                .affectedDevices(List.of(DefaultTemplateAffectedDeviceDto.builder()
                        .deviceId("device-1")
                        .deviceLabel("Living room AC")
                        .templateName("Air Conditioner")
                        .build()))
                .blockers(canApply ? List.of() : List.of(
                        cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateResetBlockerDto.builder()
                                .itemLabel("Board model")
                                .reasonCode("BOARD_MODEL_INCOMPATIBLE")
                                .reason("Incompatible board")
                                .build()))
                .environmentChanges(List.of(EnvironmentVariableChangeDto.builder()
                        .changeType(EnvironmentVariableChangeDto.ChangeType.UPDATED)
                        .name("temperature")
                        .previousValue(new BoardEnvironmentVariableDto(
                                "temperature", "24", "trusted", "private"))
                        .currentValue(new BoardEnvironmentVariableDto(
                                "temperature", "24", "trusted", "public"))
                        .build()))
                .build();
    }
}
