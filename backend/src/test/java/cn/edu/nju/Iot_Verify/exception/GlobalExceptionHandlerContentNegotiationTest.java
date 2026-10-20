package cn.edu.nju.Iot_Verify.exception;

import cn.edu.nju.Iot_Verify.service.InteractiveAiExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerContentNegotiationTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ErrorProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void workerErrorUsesAnInternalFailureEnvelopeInsteadOfRetryableUnavailable() throws Exception {
        mockMvc.perform(get("/error-probe/worker-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void resourceNotFound_retainsJsonEnvelopeForEventStreamAcceptHeader() throws Exception {
        mockMvc.perform(get("/error-probe/not-found").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Chat session not found: missing"));
    }

    @Test
    void chatBusy_retainsConflictDetailsForEventStreamAcceptHeader() throws Exception {
        mockMvc.perform(get("/error-probe/busy").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data.reasonCode").value("CHAT_SESSION_BUSY"))
                .andExpect(jsonPath("$.data.sessionId").value("busy-session"));
    }

    @Test
    void wrongMethod_retainsThe405ProtocolStatus() throws Exception {
        mockMvc.perform(get("/error-probe/json-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")))
                .andExpect(jsonPath("$.code").value(405));
    }

    @Test
    void unsupportedRequestMediaType_retainsThe415ProtocolStatus() throws Exception {
        mockMvc.perform(post("/error-probe/json-only")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(415));
    }

    @Test
    void unacceptableResponseMediaType_retainsThe406ProtocolStatus() throws Exception {
        mockMvc.perform(post("/error-probe/json-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_XML)
                        .content("{}"))
                .andExpect(status().isNotAcceptable());
    }

    @RestController
    static class ErrorProbeController {

        @GetMapping("/error-probe/worker-error")
        Object workerError() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.initialize();
            try {
                return new InteractiveAiExecutionService(executor, null)
                        .execute(1L, "worker-error-request", () -> {
                            throw new InternalError("Private worker diagnostic");
                        });
            } finally {
                executor.shutdown();
            }
        }

        @GetMapping("/error-probe/registration-phone")
        Object registrationPhone() {
            throw ConflictException.duplicatePhone("13800138000");
        }

        @GetMapping("/error-probe/registration-username")
        Object registrationUsername() {
            throw ConflictException.duplicateUsername("alice");
        }

        @GetMapping("/error-probe/template-conflict")
        Object templateConflict() {
            throw ConflictException.duplicateTemplate("Sensor");
        }

        @GetMapping("/error-probe/not-found")
        Object notFound() {
            throw ResourceNotFoundException.session("missing");
        }

        @GetMapping("/error-probe/busy")
        Object busy() {
            throw new ChatSessionBusyException("busy-session");
        }

        @GetMapping("/error-probe/board-conflict")
        Object boardConflict() {
            throw new BoardContentConflictException("DUPLICATE_RULE", "Remove the duplicate rule",
                    java.util.Map.of("rules[1]", "Remove the duplicate rule"));
        }

        @GetMapping("/error-probe/invalid-field")
        Object invalidField() {
            throw new BadRequestException("rules[1].toId: A non-blank string is required",
                    java.util.Map.of("rules[1].toId", "A non-blank string is required"));
        }

        @PostMapping(value = "/error-probe/json-only",
                consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
        Object jsonOnly() {
            return java.util.Map.of("ok", true);
        }
    }

    @Test
    void boardContentConflictRetainsStructuredReasonAndField() throws Exception {
        mockMvc.perform(get("/error-probe/board-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reasonCode").value("DUPLICATE_RULE"))
                .andExpect(jsonPath("$.data.errors['rules[1]']").value("Remove the duplicate rule"));
    }

    @Test
    void badRequestRetainsActionableFieldDiagnostics() throws Exception {
        mockMvc.perform(get("/error-probe/invalid-field"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors['rules[1].toId']").value("A non-blank string is required"));
    }

    @Test
    void registrationPhoneConflictIdentifiesTheEditableField() throws Exception {
        mockMvc.perform(get("/error-probe/registration-phone"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reasonCode").value("PHONE_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.data.errors.phone").value("Phone number is already registered."));
    }

    @Test
    void registrationUsernameConflictIdentifiesTheEditableField() throws Exception {
        mockMvc.perform(get("/error-probe/registration-username"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.reasonCode").value("USERNAME_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.data.errors.username").value("Username already exists."));
    }

    @Test
    void unrelatedConflictsDoNotClaimAnAccountField() throws Exception {
        mockMvc.perform(get("/error-probe/template-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
