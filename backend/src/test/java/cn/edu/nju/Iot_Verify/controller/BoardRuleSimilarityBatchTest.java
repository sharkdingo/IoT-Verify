package cn.edu.nju.Iot_Verify.controller;

import cn.edu.nju.Iot_Verify.component.ai.PromptCompletionService;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.exception.GlobalExceptionHandler;
import cn.edu.nju.Iot_Verify.security.CurrentUserArgumentResolver;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.service.recommendation.RecommendationResultParser;
import cn.edu.nju.Iot_Verify.service.recommendation.RuleCheckService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BoardRuleSimilarityBatchTest {
    private static final String PATH = "/api/board/rules/check-similarity/batch";
    private static final String RULE = """
            {"conditions":[{"deviceName":"sensor","attribute":"%1$s","targetType":"api"}],
             "command":{"deviceName":"lamp","action":"on"},"ruleString":"%1$s"}
            """;
    private static final String CLEAR = """
            {"index":%d,"isSimilar":false,"isDuplicate":false,"similarity":0.1,
             "mostSimilarWith":null,"duplicateWith":null,"reason":"Different action"}
            """;

    private final BoardStorageService board = mock(BoardStorageService.class);
    private final PromptCompletionService completion = mock(PromptCompletionService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L, null));
        var checks = new RuleCheckService(board, completion, mapper, new RecommendationResultParser(mapper));
        var controller = new BoardStorageController(
                board, null, null, null, null, null, null, checks, mapper, null, null, null, null);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void exactMembershipMatchesInSingleAndBatchWithoutCallingProvider() throws Exception {
        String saved = membershipRule("20,25");
        String reversed = membershipRule("25,20");
        when(board.getSemanticSnapshot(7L)).thenReturn(new BoardSemanticSnapshotDto(
                List.of(), List.of(), List.of(mapper.readValue(saved, RuleDto.class)), List.of(), List.of()));

        mvc.perform(post(PATH.replace("/batch", "")).contentType(MediaType.APPLICATION_JSON).content(reversed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reasonCode").value("EXACT_MATCH"));
        submit(batch(reversed)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reasonCode").value("EXACT_MATCH"));
        verifyNoInteractions(completion);
    }

    @Test
    void exactMembershipAgainstAnEarlierDraftSkipsTheProvider() throws Exception {
        when(board.getSemanticSnapshot(7L)).thenReturn(new BoardSemanticSnapshotDto(
                List.of(), List.of(), List.of(), List.of(), List.of()));
        submit(batch(membershipRule("20,25"), membershipRule("25,20")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].reasonCode").value("NO_EXISTING_RULES"))
                .andExpect(jsonPath("$.data[1].reasonCode").value("EXACT_MATCH"));
        verifyNoInteractions(completion);
    }

    private static String membershipRule(String values) {
        return """
                {"conditions":[{"deviceName":"sensor","attribute":"temperature","targetType":"variable",
                 "relation":"in","value":"%s"}],"command":{"deviceName":"lamp","action":"on"}}
                """.formatted(values);
    }

    @Test
    void comparesSavedAndEarlierRulesOnceAndReturnsRequestOrder() throws Exception {
        savedRule();
        when(completion.complete(anyString(), anyString(), anyDouble(), anyInt())).thenAnswer(call -> {
            JsonNode input = mapper.readTree(call.<String>getArgument(1));
            assertEquals(1, input.path("savedRules").size());
            assertEquals("candidate-1", input.at("/savedRules/0/ruleRef").textValue());
            assertEquals("batch-1", input.at("/candidates/0/ruleRef").textValue());
            assertEquals(1, input.at("/candidates/1/index").intValue());
            assertEquals(0, input.path("firstDecisionIndex").intValue());
            return results(
                    decision(1, "batch-1", true),
                    decision(0, "candidate-1", false));
        });

        submit(batch(RULE.formatted("Earlier draft"), RULE.formatted("Later draft")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].reasonCode").value("AI_SIMILAR"))
                .andExpect(jsonPath("$.data[0].matchedRule").value("Saved rule"))
                .andExpect(jsonPath("$.data[1].reasonCode").value("AI_DUPLICATE"))
                .andExpect(jsonPath("$.data[1].requiresReview").value(true))
                .andExpect(jsonPath("$.data[1].matchedRule").value("Earlier draft"));
        verify(board).getSemanticSnapshot(7L);
        verifyNoMoreInteractions(board);
        verify(completion).complete(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void noSavedRulesLeavesTheFirstDraftAvailableOnlyAsAReference() throws Exception {
        stubRules(List.of());
        when(completion.complete(anyString(), anyString(), anyDouble(), anyInt())).thenAnswer(call -> {
            JsonNode input = mapper.readTree(call.<String>getArgument(1));
            assertEquals(0, input.path("savedRules").size());
            assertEquals("batch-1", input.at("/candidates/0/ruleRef").textValue());
            assertEquals(1, input.path("firstDecisionIndex").intValue());
            return results(decision(1, "batch-1", false));
        });

        submit(batch(RULE.formatted("First draft"), RULE.formatted("Second draft")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].reasonCode").value("NO_EXISTING_RULES"))
                .andExpect(jsonPath("$.data[0].requiresReview").value(false))
                .andExpect(jsonPath("$.data[1].matchedRule").value("First draft"));
        verify(completion).complete(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void oneDraftWithoutReferencesNeedsNoProvider() throws Exception {
        stubRules(List.of());
        submit(batch(RULE.formatted("Only draft")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].reasonCode").value("NO_EXISTING_RULES"));
        verifyNoInteractions(completion);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void rejectsInvalidRequestsBeforeReadingRules(String name, String body, String field) throws Exception {
        submit(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors['" + field + "']").isString());
        verifyNoInteractions(board, completion);
    }

    private static Stream<Arguments> invalidRequests() {
        String validRule = RULE.formatted("Draft");
        return Stream.of(
                Arguments.of("missing rules", "{}", "rules"),
                Arguments.of("empty batch", batch(), "rules"),
                Arguments.of("eleven rules", batch(Collections.nCopies(11, validRule).toArray(String[]::new)), "rules"),
                Arguments.of("null candidate", batch("null"), "rules[0]"),
                Arguments.of("missing condition targetType",
                        batch(validRule.replace("\"targetType\":\"api\"", "\"value\":null")),
                        "rules[0].conditions[0].targetType"),
                Arguments.of("missing command action", batch(validRule.replace("\"action\":\"on\"", "\"action\":null")),
                        "rules[0].command.action"));
    }

    @Test
    void rejectsAnIdEvenOnALaterDraftBeforeReadingRules() throws Exception {
        String edit = RULE.formatted("Edit").replace("\"ruleString\"", "\"id\":17,\"ruleString\"");
        submit(batch(RULE.formatted("Draft"), edit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("rules[1].id")));
        verifyNoInteractions(board, completion);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidReplies")
    void rejectsTheWholeBatchForAnUnusableProviderResponse(String name, String reply) throws Exception {
        savedRule();
        when(completion.complete(anyString(), anyString(), anyDouble(), anyInt())).thenReturn(reply);

        submit(batch(RULE.formatted("Draft A"), RULE.formatted("Draft B")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502));
        verify(completion).complete(anyString(), anyString(), anyDouble(), anyInt());
    }

    private static Stream<Arguments> invalidReplies() {
        String first = CLEAR.formatted(0);
        String second = CLEAR.formatted(1);
        return Stream.of(
                Arguments.of("missing decision", results(first)),
                Arguments.of("duplicate index", results(first, first)),
                Arguments.of("out-of-range index", results(first, CLEAR.formatted(2))),
                Arguments.of("fractional index", results(first, second.replace("\"index\":1", "\"index\":1.0"))),
                Arguments.of("duplicate JSON field", results(first, second.replace("\"index\":1", "\"index\":0,\"index\":1"))),
                Arguments.of("additional JSON document", results(first, second) + "{}"),
                Arguments.of("missing reference field", results(first, second.replace("\"duplicateWith\":null,", ""))),
                Arguments.of("unknown reference", results(first, decision(1, "candidate-2", true))),
                Arguments.of("own reference", results(first, decision(1, "batch-2", true))),
                Arguments.of("later reference", results(decision(0, "batch-2", false), second)),
                Arguments.of("non-text reference", results(first, second.replace("\"mostSimilarWith\":null", "\"mostSimilarWith\":5"))),
                Arguments.of("similar without a match", results(first, second.replace("\"isSimilar\":false", "\"isSimilar\":true"))),
                Arguments.of("score in wrong type", results(first, second.replace("0.1", "\"0.1\""))));
    }

    private void savedRule() throws Exception {
        stubRules(List.of(mapper.readValue(RULE.formatted("Saved rule"), RuleDto.class)));
    }

    private void stubRules(List<RuleDto> rules) {
        when(board.getSemanticSnapshot(7L)).thenReturn(new BoardSemanticSnapshotDto(
                List.of(), List.of(), rules, List.of(), List.of()));
    }

    private ResultActions submit(String body) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String batch(String... rules) {
        return "{\"rules\":[" + String.join(",", rules) + "]}";
    }

    private static String results(String... decisions) {
        return "{\"results\":[" + String.join(",", decisions) + "]}";
    }

    private static String decision(int index, String ref, boolean duplicate) {
        return """
                {"index":%d,"isSimilar":true,"isDuplicate":%b,"similarity":0.95,
                 "mostSimilarWith":"%s","duplicateWith":%s,"reason":"Same trigger and action"}
                """.formatted(index, duplicate, ref, duplicate ? "\"" + ref + "\"" : "null");
    }
}
