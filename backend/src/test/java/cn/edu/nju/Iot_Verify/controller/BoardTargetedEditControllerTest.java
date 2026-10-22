package cn.edu.nju.Iot_Verify.controller;

import cn.edu.nju.Iot_Verify.exception.GlobalExceptionHandler;
import cn.edu.nju.Iot_Verify.exception.BoardContentConflictException;
import cn.edu.nju.Iot_Verify.dto.board.CollectionMutationResultDto;
import cn.edu.nju.Iot_Verify.security.CurrentUserArgumentResolver;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class BoardTargetedEditControllerTest {
    private final BoardStorageService service = mock(BoardStorageService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L, null));
        BoardStorageController controller = new BoardStorageController(
                service, null, null, null, null, null, null, null, null, null, null, null, null);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"rules", "specs"})
    void acceptsOneCompleteExpectedAndDesiredRecord(String collection) throws Exception {
        String item = item(collection);
        if ("rules".equals(collection)) {
            when(service.updateRuleIfUnchanged(eq(7L), eq(1L), any(), any()))
                    .thenAnswer(call -> {
                        var desired = call.getArgument(3, cn.edu.nju.Iot_Verify.dto.rule.RuleDto.class);
                        return CollectionMutationResultDto.of("updated", desired, java.util.List.of(desired));
                    });
        } else {
            when(service.updateSpecIfUnchanged(eq(7L), eq("spec-1"), any(), any()))
                    .thenAnswer(call -> {
                        var desired = call.getArgument(3, cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto.class);
                        return CollectionMutationResultDto.of("updated", desired, java.util.List.of(desired));
                    });
        }
        mvc.perform(put(path(collection)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected\":" + item + ",\"desired\":" + item.replace("\"on\"", "\"off\"") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation").value("updated"));
        if ("rules".equals(collection)) {
            verify(service).updateRuleIfUnchanged(eq(7L), eq(1L),
                    argThat(before -> "on".equals(before.getConditions().get(0).getValue())),
                    argThat(after -> "off".equals(after.getConditions().get(0).getValue())));
        } else {
            verify(service).updateSpecIfUnchanged(eq(7L), eq("spec-1"),
                    argThat(before -> "on".equals(before.getAConditions().get(0).getValue())),
                    argThat(after -> "off".equals(after.getAConditions().get(0).getValue())));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rules", "specs"})
    void staleEditReturnsAStableConflictCode(String collection) throws Exception {
        String reasonCode = "rules".equals(collection) ? "RULE_EDIT_STALE" : "SPEC_EDIT_STALE";
        var conflict = new BoardContentConflictException(reasonCode, "The edited record changed.",
                java.util.Map.of("expected", "Refresh the record before editing again."));
        if ("rules".equals(collection)) {
            when(service.updateRuleIfUnchanged(eq(7L), eq(1L), any(), any())).thenThrow(conflict);
        } else {
            when(service.updateSpecIfUnchanged(eq(7L), eq("spec-1"), any(), any())).thenThrow(conflict);
        }
        mvc.perform(put(path(collection)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected\":" + item(collection) + ",\"desired\":" + item(collection) + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reasonCode").value(reasonCode))
                .andExpect(jsonPath("$.data.errors.expected").isString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"rules", "specs"})
    void missingBaselineCannotReachTheMutation(String collection) throws Exception {
        mvc.perform(put(path(collection)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"desired\":" + item(collection) + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rules", "specs"})
    void malformedDesiredConditionsCannotReachTheMutation(String collection) throws Exception {
        String desired = item(collection).replace("\"on\"", "null");
        mvc.perform(put(path(collection)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected\":" + item(collection) + ",\"desired\":" + desired + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private String path(String collection) {
        return "/api/board/" + collection + ("rules".equals(collection) ? "/1" : "/spec-1");
    }

    private String item(String collection) {
        return "rules".equals(collection) ? """
                {"id":1,"conditions":[{"deviceName":"light-1","targetType":"state","attribute":"state",
                "relation":"=","value":"on"}],"command":{"deviceName":"light-1","action":"off"}}
                """ : """
                {"id":"spec-1","templateId":"1","aConditions":[{"deviceId":"light-1","targetType":"state",
                "key":"state","relation":"=","value":"on","side":"a"}]}
                """;
    }
}
