package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.configure.ChatExecutionConfig;
import cn.edu.nju.Iot_Verify.configure.JwtConfig;
import cn.edu.nju.Iot_Verify.dto.fix.FixApplyResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.fix.ParameterAdjustment;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRange;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRangeSelection;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.FixApplyPreflightUnavailableException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.FixService;
import cn.edu.nju.Iot_Verify.service.FixSuggestionTokenService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplyFixToolTest {

    @Mock
    private FixService fixService;

    private ObjectMapper objectMapper;
    private FixSuggestionTokenService tokenService;
    private AiDestructiveActionGuard guard;
    private ChatExecutionConfig chatExecutionConfig;
    private ApplyFixTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        JwtConfig jwtConfig = new JwtConfig();
        jwtConfig.setSecret("apply-fix-tool-test-secret-long-enough-for-hmac");
        tokenService = new FixSuggestionTokenService(objectMapper, jwtConfig);
        guard = new AiDestructiveActionGuard(objectMapper);
        chatExecutionConfig = new ChatExecutionConfig();
        tool = new ApplyFixTool(
                fixService, tokenService, objectMapper, guard, chatExecutionConfig);
        UserContextHolder.setUserId(7L);
        UserContextHolder.setChatSessionId("apply-fix-session");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void previewThenConfirmedApply_usesStoredSignedProposalAndExistingFixServiceBoundary() throws Exception {
        FixSuggestionDto suggestion = signedPublicRemovalSuggestion();
        JsonNode preview = objectMapper.readTree(tool.execute(previewArgs(suggestion).toString()));

        assertEquals("preview", preview.path("operation").asText());
        assertTrue(preview.path("requiresUserConfirmation").asBoolean());
        assertEquals("remove", preview.path("suggestion").path("strategy").asText());
        assertFalse(preview.path("suggestion").has("suggestionToken"));
        verifyNoInteractions(fixService);

        when(fixService.applyFix(
                eq(7L), eq(31L), eq("remove"), eq(suggestion),
                eq(suggestion.getSuggestionToken()), eq(null)))
                .thenReturn(appliedResult(suggestion, 3, 2));

        UserContextHolder.setDestructiveActionConfirmed(true);
        JsonNode applied = objectMapper.readTree(tool.execute("""
                {"traceId":31,"confirmed":true,"impactToken":"%s"}
                """.formatted(preview.path("impactToken").asText())));

        assertEquals("applied", applied.path("operation").asText());
        assertTrue(applied.path("applied").asBoolean());
        assertEquals(3, applied.path("previousRuleCount").asInt());
        assertEquals(2, applied.path("currentRuleCount").asInt());
        assertTrue(applied.path("verificationEvidenceReused").asBoolean());
        verify(fixService).applyFix(
                7L, 31L, "remove", suggestion, suggestion.getSuggestionToken(), null);
    }

    @Test
    void confirmedApply_acceptsThePreviewArgumentsResentUnchanged() throws Exception {
        FixSuggestionDto suggestion = signedPublicRemovalSuggestion();
        JsonNode preview = objectMapper.readTree(tool.execute(previewArgs(suggestion).toString()));

        when(fixService.applyFix(
                eq(7L), eq(31L), eq("remove"), eq(suggestion),
                eq(suggestion.getSuggestionToken()), eq(null)))
                .thenReturn(appliedResult(suggestion, 3, 2));

        // The model reaches the confirmed call by resending the object it previewed with plus the
        // token. Rejecting the carried-over fields spent a whole round on a guaranteed
        // VALIDATION_ERROR while no description said they had to be dropped.
        //
        // Resend a TAMPERED copy, not the original: the confirmed path never re-verifies the
        // signature (that happens only in preview), so if it read `suggestion` from the arguments
        // instead of the stored payload, a caller could swap in a proposal the user never reviewed.
        // Asserting against the pristine DTO is what makes that a red test rather than a silent pass.
        ObjectNode confirmedArgs = previewArgs(suggestion);
        ObjectNode tampered = (ObjectNode) confirmedArgs.get("suggestion");
        tampered.set("removedRuleDescriptions",
                objectMapper.createArrayNode().add("a rule the user never approved removing"));
        confirmedArgs.put("confirmed", true);
        confirmedArgs.put("impactToken", preview.path("impactToken").asText());

        UserContextHolder.setDestructiveActionConfirmed(true);
        JsonNode applied = objectMapper.readTree(tool.execute(confirmedArgs.toString()));

        assertEquals("applied", applied.path("operation").asText());
        // The stored proposal is what gets applied, so the tampered copy cannot influence the write.
        verify(fixService).applyFix(
                7L, 31L, "remove", suggestion, suggestion.getSuggestionToken(), null);
    }

    @Test
    void omittedConfirmedPreviewsRatherThanFailingValidation() throws Exception {
        FixSuggestionDto suggestion = signedPublicRemovalSuggestion();
        ObjectNode args = previewArgs(suggestion);
        args.remove("confirmed");

        // Every other confirmation-gated tool defaults a missing `confirmed` to false. Failing here
        // instead reached the same no-write outcome one wasted round later.
        JsonNode result = objectMapper.readTree(tool.execute(args.toString()));

        assertEquals("preview", result.path("operation").asText());
        assertTrue(result.path("requiresUserConfirmation").asBoolean());
        verifyNoInteractions(fixService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void definitionClosesRootAndNestedMutationArguments() {
        var definition = tool.getDefinition();
        Map<String, Object> suggestionSchema = (Map<String, Object>)
                definition.parameters().properties.get("suggestion");
        Map<String, Object> parameterItems = (Map<String, Object>)
                ((Map<String, Object>) suggestionSchema.get("properties"))
                        .get("parameterAdjustments");
        Map<String, Object> preferredRangeItems = (Map<String, Object>)
                ((Map<String, Object>) definition.parameters().properties
                        .get("preferredRangeSelections")).get("items");

        assertFalse(definition.parameters().additionalProperties);
        assertEquals(List.of("traceId", "confirmed"), definition.parameters().required);
        assertEquals(false, suggestionSchema.get("additionalProperties"));
        assertEquals(false,
                ((Map<String, Object>) parameterItems.get("items")).get("additionalProperties"));
        assertEquals(false, preferredRangeItems.get("additionalProperties"));
    }

    @Test
    void preview_rejectsUnknownAndMalformedSuggestionFieldsBeforeAnyMutation() throws Exception {
        FixSuggestionDto suggestion = signedPublicRemovalSuggestion();
        ObjectNode args = previewArgs(suggestion);
        ((ObjectNode) args.path("suggestion")).put("removedRuleIndices", "0");

        JsonNode result = objectMapper.readTree(tool.execute(args.toString()));

        assertEquals("VALIDATION_ERROR", result.path("errorCode").asText());
        assertEquals(400, result.path("status").asInt());
        verifyNoInteractions(fixService);
    }

    @Test
    void preview_rejectsAPreexistingViolationMissingAFieldTheSchemaRequires() throws Exception {
        // The schema marks every field required; accepting a partial entry meant the model could drop
        // the formula the user needs to recognise which specification stays violated.
        ObjectNode args = previewArgs(signedPublicRemovalSuggestion());
        ((ObjectNode) args.path("suggestion")).putArray("preexistingViolations")
                .addObject().put("specId", "s2").put("templateId", "1");

        JsonNode result = objectMapper.readTree(tool.execute(args.toString()));

        assertEquals("VALIDATION_ERROR", result.path("errorCode").asText());
        assertTrue(result.path("error").asText().contains("preexistingViolations[0].formulaPreview"),
                result.path("error").asText());
        verifyNoInteractions(fixService);
    }

    @Test
    void preview_rejectsTamperedSuggestionUnderItsOriginalSignature() throws Exception {
        FixSuggestionDto suggestion = signedPublicRemovalSuggestion();
        ObjectNode args = previewArgs(suggestion);
        ((ObjectNode) args.path("suggestion")).put("description", "Remove a different automation");

        JsonNode result = objectMapper.readTree(tool.execute(args.toString()));

        assertEquals("BAD_REQUEST", result.path("errorCode").asText());
        assertEquals(400, result.path("status").asInt());
        assertTrue(result.path("error").asText().contains("stale"));
        verifyNoInteractions(fixService);
    }

    @Test
    void preview_serializationFailureClearsTheUndeliveredConfirmation() throws Exception {
        ObjectMapper failingMapper = spy(new ObjectMapper());
        JwtConfig jwtConfig = new JwtConfig();
        jwtConfig.setSecret("apply-fix-tool-preview-test-secret-long-enough");
        FixSuggestionTokenService localTokenService = new FixSuggestionTokenService(
                new ObjectMapper(), jwtConfig);
        AiDestructiveActionGuard localGuard = new AiDestructiveActionGuard(failingMapper);
        ApplyFixTool failingTool = new ApplyFixTool(
                fixService, localTokenService, failingMapper, localGuard, chatExecutionConfig);
        FixSuggestionDto serverSuggestion = removalSuggestion();
        String token = localTokenService.issue(7L, 31L, serverSuggestion, null);
        FixSuggestionDto publicSuggestion = objectMapper.readValue(
                objectMapper.writeValueAsBytes(serverSuggestion), FixSuggestionDto.class);
        publicSuggestion.setSuggestionToken(token);
        doThrow(new JsonProcessingException("forced preview response failure") { })
                .when(failingMapper).writeValueAsString(any(Object.class));

        JsonNode result = objectMapper.readTree(
                failingTool.execute(previewArgs(publicSuggestion).toString()));

        assertEquals("RESULT_UNAVAILABLE", result.path("resultStatus").asText());
        assertTrue(Set.of("PREVIEW_RESULT_INVALID", "").contains(result.path("errorCode").asText()));
        assertFalse(result.path("mutationMayHaveCommitted").asBoolean(true));
        assertTrue(localGuard.pendingContext(7L, "apply-fix-session").isEmpty());
        verifyNoInteractions(fixService);
    }

    @Test
    void preview_oversizedResultClearsConfirmationBeforeTheManagerCanDiscardIt() throws Exception {
        chatExecutionConfig.setMaxToolResultBytes(4096);
        FixSuggestionDto serverSuggestion = removalSuggestion();
        serverSuggestion.setDescription("review ".repeat(900));
        String token = tokenService.issue(7L, 31L, serverSuggestion, null);
        FixSuggestionDto publicSuggestion = objectMapper.readValue(
                objectMapper.writeValueAsBytes(serverSuggestion), FixSuggestionDto.class);
        publicSuggestion.setSuggestionToken(token);

        JsonNode result = objectMapper.readTree(
                tool.execute(previewArgs(publicSuggestion).toString()));

        assertEquals("RESULT_UNAVAILABLE", result.path("resultStatus").asText());
        assertEquals("TOOL_RESULT_TOO_LARGE", result.path("errorCode").asText());
        assertFalse(result.path("mutationMayHaveCommitted").asBoolean(true));
        assertTrue(guard.pendingContext(7L, "apply-fix-session").isEmpty());
        verifyNoInteractions(fixService);
    }

    @Test
    void preview_requiresTheExactSignedPreferredRangeSelections() throws Exception {
        String targetId = PreferredRangeSelection.targetIdFor(31L, 0, 0);
        FixSuggestionDto serverSuggestion = FixSuggestionDto.builder()
                .strategy("parameter")
                .description("Adjust the temperature threshold")
                .parameterAdjustments(List.of(ParameterAdjustment.builder()
                        .targetId(targetId)
                        .ruleIndex(0)
                        .conditionIndex(0)
                        .attribute("temperature")
                        .relation(">")
                        .originalValue("30")
                        .newValue("25")
                        .lowerBound(10)
                        .upperBound(40)
                        .description("Lower the trigger threshold")
                        .build()))
                .build();
        String signedToken = tokenService.issue(
                7L, 31L, serverSuggestion, Map.of(targetId, new PreferredRange(10, 20)));
        FixSuggestionDto publicSuggestion = objectMapper.readValue(
                objectMapper.writeValueAsBytes(serverSuggestion), FixSuggestionDto.class);
        publicSuggestion.setSuggestionToken(signedToken);
        ObjectNode exactArgs = previewArgs(publicSuggestion);
        exactArgs.putArray("preferredRangeSelections").addObject()
                .put("targetId", targetId).put("lower", 10).put("upper", 20);

        JsonNode exact = objectMapper.readTree(tool.execute(exactArgs.toString()));

        assertEquals("preview", exact.path("operation").asText());
        ObjectNode changedArgs = previewArgs(publicSuggestion);
        changedArgs.putArray("preferredRangeSelections").addObject()
                .put("targetId", targetId).put("lower", 11).put("upper", 20);
        JsonNode changed = objectMapper.readTree(tool.execute(changedArgs.toString()));
        assertEquals("BAD_REQUEST", changed.path("errorCode").asText());
        verifyNoInteractions(fixService);
    }

    @Test
    void confirmedApply_rejectsMismatchedConfirmationWithoutConsumingTheRealOne() throws Exception {
        FixSuggestionDto suggestion = signedPublicRemovalSuggestion();
        JsonNode preview = objectMapper.readTree(tool.execute(previewArgs(suggestion).toString()));
        String realToken = preview.path("impactToken").asText();
        UserContextHolder.setDestructiveActionConfirmed(true);

        JsonNode mismatch = objectMapper.readTree(tool.execute(
                "{\"traceId\":31,\"confirmed\":true,\"impactToken\":\"wrong-token\"}"));

        assertEquals("CONFIRMATION_MISMATCH", mismatch.path("errorCode").asText());
        verifyNoInteractions(fixService);

        when(fixService.applyFix(any(), any(), any(), any(), any(), any()))
                .thenReturn(appliedResult(suggestion, 2, 1));
        JsonNode applied = objectMapper.readTree(tool.execute("""
                {"traceId":31,"confirmed":true,"impactToken":"%s"}
                """.formatted(realToken)));

        assertEquals("applied", applied.path("operation").asText());
        verify(fixService).applyFix(any(), any(), any(), any(), any(), any());
    }

    @Test
    void confirmedApply_reportsExpiredOrMissingConfirmationWithoutCallingFixService() throws Exception {
        AiDestructiveActionGuard expiredGuard = org.mockito.Mockito.mock(AiDestructiveActionGuard.class);
        when(expiredGuard.consumeStoredAction(7L, "apply_fix", "31", "expired-token"))
                .thenReturn(new AiDestructiveActionGuard.ConsumeResult(
                        false,
                        "CONFIRMATION_MISSING",
                        "No changes were made. The preview is missing or expired.",
                        null,
                        null));
        ApplyFixTool expiredTool = new ApplyFixTool(
                fixService, tokenService, objectMapper, expiredGuard, chatExecutionConfig);
        UserContextHolder.setDestructiveActionConfirmed(true);

        JsonNode result = objectMapper.readTree(expiredTool.execute(
                "{\"traceId\":31,\"confirmed\":true,\"impactToken\":\"expired-token\"}"));

        assertEquals("CONFIRMATION_MISSING", result.path("errorCode").asText());
        assertEquals(409, result.path("status").asInt());
        assertTrue(result.path("requiresUserConfirmation").asBoolean());
        verifyNoInteractions(fixService);
    }

    @Test
    void confirmedApply_serializationFailureReportsMutationOutcomeUnavailable() throws Exception {
        ObjectMapper failingMapper = spy(new ObjectMapper());
        JwtConfig jwtConfig = new JwtConfig();
        jwtConfig.setSecret("apply-fix-tool-result-test-secret-long-enough");
        FixSuggestionTokenService localTokenService = new FixSuggestionTokenService(new ObjectMapper(), jwtConfig);
        AiDestructiveActionGuard localGuard = new AiDestructiveActionGuard(failingMapper);
        ApplyFixTool failingTool = new ApplyFixTool(
                fixService, localTokenService, failingMapper, localGuard, chatExecutionConfig);
        FixSuggestionDto serverSuggestion = removalSuggestion();
        String token = localTokenService.issue(7L, 31L, serverSuggestion, null);
        FixSuggestionDto publicSuggestion = new ObjectMapper().readValue(
                new ObjectMapper().writeValueAsBytes(serverSuggestion), FixSuggestionDto.class);
        publicSuggestion.setSuggestionToken(token);
        JsonNode preview = objectMapper.readTree(
                failingTool.execute(previewArgs(publicSuggestion).toString()));

        when(fixService.applyFix(any(), any(), any(), any(), any(), any()))
                .thenReturn(appliedResult(publicSuggestion, 2, 1));
        doThrow(new JsonProcessingException("forced response failure") { })
                .when(failingMapper).writeValueAsString(any(Object.class));
        UserContextHolder.setDestructiveActionConfirmed(true);

        JsonNode result = objectMapper.readTree(failingTool.execute("""
                {"traceId":31,"confirmed":true,"impactToken":"%s"}
                """.formatted(preview.path("impactToken").asText())));

        assertEquals("RESULT_UNAVAILABLE", result.path("resultStatus").asText());
        assertFalse(result.path("resultAvailable").asBoolean(true));
        assertTrue(result.path("mutationMayHaveCommitted").asBoolean());
        verify(fixService).applyFix(any(), any(), any(), any(), any(), any());
    }

    @Test
    void confirmedApply_incompleteServiceResultReportsMutationOutcomeUnavailable() throws Exception {
        FixSuggestionDto suggestion = signedPublicRemovalSuggestion();
        JsonNode preview = objectMapper.readTree(tool.execute(previewArgs(suggestion).toString()));
        when(fixService.applyFix(any(), any(), any(), any(), any(), any())).thenReturn(null);
        UserContextHolder.setDestructiveActionConfirmed(true);

        JsonNode result = objectMapper.readTree(tool.execute("""
                {"traceId":31,"confirmed":true,"impactToken":"%s"}
                """.formatted(preview.path("impactToken").asText())));

        assertEquals("RESULT_UNAVAILABLE", result.path("resultStatus").asText());
        assertEquals("MUTATION_RESULT_INVALID", result.path("errorCode").asText());
        assertFalse(result.path("resultAvailable").asBoolean(true));
        assertTrue(result.path("mutationMayHaveCommitted").asBoolean());
    }

    @Test
    void confirmedApply_unexpectedServiceSettlementFailureReportsUnknownMutationOutcome() throws Exception {
        FixSuggestionDto suggestion = signedPublicRemovalSuggestion();
        JsonNode preview = objectMapper.readTree(tool.execute(previewArgs(suggestion).toString()));
        when(fixService.applyFix(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("commit acknowledgement lost"));
        UserContextHolder.setDestructiveActionConfirmed(true);

        JsonNode result = objectMapper.readTree(tool.execute("""
                {"traceId":31,"confirmed":true,"impactToken":"%s"}
                """.formatted(preview.path("impactToken").asText())));

        assertEquals("RESULT_UNAVAILABLE", result.path("resultStatus").asText());
        assertEquals("MUTATION_RESULT_INVALID", result.path("errorCode").asText());
        assertTrue(result.path("mutationMayHaveCommitted").asBoolean());
    }

    @Test
    void confirmedApply_generalAdmissionFailureReportsUnknownMutationOutcome() throws Exception {
        FixSuggestionDto suggestion = signedPublicRemovalSuggestion();
        JsonNode preview = objectMapper.readTree(tool.execute(previewArgs(suggestion).toString()));
        when(fixService.applyFix(any(), any(), any(), any(), any(), any()))
                .thenThrow(new ServiceUnavailableException("lease lost during settlement"));
        UserContextHolder.setDestructiveActionConfirmed(true);

        JsonNode result = objectMapper.readTree(tool.execute("""
                {"traceId":31,"confirmed":true,"impactToken":"%s"}
                """.formatted(preview.path("impactToken").asText())));

        assertEquals("RESULT_UNAVAILABLE", result.path("resultStatus").asText());
        assertTrue(result.path("mutationMayHaveCommitted").asBoolean());
    }

    @Test
    void confirmedApply_knownPreflightOutageRemainsASafeRetryableFailure() throws Exception {
        FixSuggestionDto suggestion = signedPublicRemovalSuggestion();
        JsonNode preview = objectMapper.readTree(tool.execute(previewArgs(suggestion).toString()));
        when(fixService.applyFix(any(), any(), any(), any(), any(), any()))
                .thenThrow(new FixApplyPreflightUnavailableException("template snapshot unavailable"));
        UserContextHolder.setDestructiveActionConfirmed(true);

        JsonNode result = objectMapper.readTree(tool.execute("""
                {"traceId":31,"confirmed":true,"impactToken":"%s"}
                """.formatted(preview.path("impactToken").asText())));

        assertEquals("SERVICE_UNAVAILABLE", result.path("errorCode").asText());
        assertEquals(503, result.path("status").asInt());
        assertFalse(result.has("mutationMayHaveCommitted"));
    }

    @Test
    void confirmedApply_inconsistentAppliedSuggestionReportsUnknownMutationOutcome() throws Exception {
        FixSuggestionDto suggestion = signedPublicRemovalSuggestion();
        JsonNode preview = objectMapper.readTree(tool.execute(previewArgs(suggestion).toString()));
        FixSuggestionDto differentSuggestion = removalSuggestion();
        differentSuggestion.setDescription("Remove an unrelated automation");
        when(fixService.applyFix(any(), any(), any(), any(), any(), any()))
                .thenReturn(appliedResult(differentSuggestion, 2, 1));
        UserContextHolder.setDestructiveActionConfirmed(true);

        JsonNode result = objectMapper.readTree(tool.execute("""
                {"traceId":31,"confirmed":true,"impactToken":"%s"}
                """.formatted(preview.path("impactToken").asText())));

        assertEquals("RESULT_UNAVAILABLE", result.path("resultStatus").asText());
        assertEquals("MUTATION_RESULT_INVALID", result.path("errorCode").asText());
        assertTrue(result.path("mutationMayHaveCommitted").asBoolean());
    }

    @Test
    void fuzzFindingArgumentsAreNotAcceptedAsFormalFixEvidence() throws Exception {
        JsonNode result = objectMapper.readTree(tool.execute(
                "{\"traceId\":31,\"findingId\":9,\"confirmed\":false}"));

        assertEquals("VALIDATION_ERROR", result.path("errorCode").asText());
        verifyNoInteractions(fixService);
    }

    private FixSuggestionDto signedPublicRemovalSuggestion() throws Exception {
        FixSuggestionDto serverSuggestion = removalSuggestion();
        String token = tokenService.issue(7L, 31L, serverSuggestion, null);
        FixSuggestionDto publicSuggestion = objectMapper.readValue(
                objectMapper.writeValueAsBytes(serverSuggestion), FixSuggestionDto.class);
        publicSuggestion.setSuggestionToken(token);
        return publicSuggestion;
    }

    private FixSuggestionDto removalSuggestion() {
        return FixSuggestionDto.builder()
                .strategy("remove")
                .description("Remove the conflicting nighttime unlock automation")
                .removedRuleIndices(List.of(1))
                .removedRuleDescriptions(List.of("When presence is detected, unlock the front door"))
                .build();
    }

    private FixApplyResultDto appliedResult(FixSuggestionDto suggestion, int previous, int current) {
        List<RuleDto> rules = IntStream.range(0, current)
                .mapToObj(index -> RuleDto.builder().ruleString("Rule " + index).build())
                .toList();
        return FixApplyResultDto.builder()
                .applied(true)
                .strategy("remove")
                .verificationEvidenceReused(true)
                .appliedSuggestion(suggestion)
                .previousRuleCount(previous)
                .currentRuleCount(current)
                .message("Removed one conflicting automation using signed verification evidence.")
                .rules(rules)
                .build();
    }

    private ObjectNode previewArgs(FixSuggestionDto suggestion) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("traceId", 31);
        args.put("confirmed", false);
        args.set("suggestion", objectMapper.valueToTree(suggestion));
        return args;
    }
}
