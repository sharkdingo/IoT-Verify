package cn.edu.nju.Iot_Verify.controller;

import cn.edu.nju.Iot_Verify.exception.GlobalExceptionHandler;
import cn.edu.nju.Iot_Verify.security.CurrentUserArgumentResolver;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BoardNodeAddressingTest {
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
    @ValueSource(strings = {"slash/id", "back\\slash", "percent%2F", "query?id", "fragment#id",
            "internal space", ".", "..", "semi;colon", "plus+id", "left,right"})
    void everyTargetedEndpointPassesTheExactQueryIdentityToTheOwnedService(String nodeId) throws Exception {
        for (String operation : new String[]{"layout", "runtime", "label", "deletion-preview", "delete"}) {
            mvc.perform(command(operation).queryParam("nodeId", nodeId)).andExpect(status().isOk());
        }
        verify(service).updateNodeLayout(eq(7L), eq(nodeId), any());
        verify(service).updateNodeRuntime(eq(7L), eq(nodeId), any());
        verify(service).renameNode(7L, nodeId, "New label", "Old label");
        verify(service).previewNodeDeletion(7L, nodeId);
        verify(service).deleteNodeCascade(7L, nodeId, "confirmed-token");
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"layout", "runtime", "label", "deletion-preview", "delete"})
    void missingQueryIdentityIsRejectedBeforeReachingTheService(String operation) throws Exception {
        mvc.perform(command(operation)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"layout", "runtime", "label", "deletion-preview", "delete"})
    void differentRepeatedQueryIdentitiesAreRejectedBeforeReachingTheService(String operation) throws Exception {
        mvc.perform(command(operation).queryParam("nodeId", "left", "right"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"layout", "runtime", "label", "deletion-preview", "delete"})
    void identicalRepeatedQueryIdentitiesAreRejectedBeforeReachingTheService(String operation) throws Exception {
        mvc.perform(command(operation).queryParam("nodeId", "left", "left"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private MockHttpServletRequestBuilder command(String operation) {
        HttpMethod method = switch (operation) {
            case "layout", "runtime" -> HttpMethod.PUT;
            case "label" -> HttpMethod.PATCH;
            case "deletion-preview" -> HttpMethod.GET;
            default -> HttpMethod.POST;
        };
        String body = switch (operation) {
            case "layout" -> "{\"position\":{\"x\":20,\"y\":30},\"width\":176,\"height\":128}";
            case "runtime" -> "{\"expected\":{},\"desired\":{}}";
            case "label" -> "{\"label\":\"New label\",\"expectedLabel\":\"Old label\"}";
            case "delete" -> "{\"impactToken\":\"confirmed-token\"}";
            default -> "";
        };
        return request(method, "/api/board/nodes/" + operation).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
