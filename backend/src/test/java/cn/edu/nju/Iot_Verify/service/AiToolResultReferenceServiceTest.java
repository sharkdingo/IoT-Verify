package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolCall;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.testutil.AiToolReadFixture;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class AiToolResultReferenceServiceTest {
    private static final String VERSION = "v1:" + "a".repeat(64);
    private final AiToolReadFixture reads = new AiToolReadFixture();

    @ParameterizedTest
    @CsvSource({"list_rules,rule", "list_specs,spec", "board_overview,rule", "board_overview,spec"})
    void resolvesTheExactVersionFromTheProjectedRead(String source, String kind) throws Exception {
        String ref = read(source, kind);
        assertEquals(VERSION, reads.service.resolveBoardVersion(1L, "owned", ref, kind, "17"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"other-owner", "other-session", "missing-message", "wrong-kind", "wrong-target", "wrong-index"})
    void rejectsReferencesOutsideTheirOwnerSessionAndTarget(String mismatch) throws Exception {
        String ref = read("list_rules", "rule");
        Long owner = "other-owner".equals(mismatch) ? 2L : 1L;
        String session = "other-session".equals(mismatch) ? "other" : "owned";
        String selected = "missing-message".equals(mismatch) ? "999:rule:1"
                : "wrong-index".equals(mismatch) ? "521:rule:2" : ref;
        String kind = "wrong-kind".equals(mismatch) ? "spec" : "rule";
        String target = "wrong-target".equals(mismatch) ? "18" : "17";
        assertThrows(BadRequestException.class,
                () -> reads.service.resolveBoardVersion(owner, session, selected, kind, target));
    }

    @ParameterizedTest
    @ValueSource(strings = {"manage_rule", "list_specs", "missing-request", "different-call"})
    void rejectsNonReadSourcesAndUnpairedToolResults(String source) throws Exception {
        String ref = read("list_rules", "rule");
        if ("missing-request".equals(source)) {
            when(reads.messages.findFirstBySessionIdAndIdLessThanAndRoleOrderByIdDesc("owned", 521L, "assistant"))
                    .thenReturn(Optional.empty());
        } else {
            reads.lastRequest.setContent(reads.codec.serializeToolCalls(List.of(new LlmToolCall(
                    "different-call".equals(source) ? "unrelated-call" : "read-521", source, "{}"))));
        }
        assertThrows(BadRequestException.class,
                () -> reads.service.resolveBoardVersion(1L, "owned", ref, "rule", "17"));
    }

    @Test
    void rejectsADuplicateCallIdentityInsteadOfChoosingItsFirstSource() throws Exception {
        String ref = read("list_rules", "rule");
        reads.lastRequest.setContent(reads.codec.serializeToolCalls(List.of(
                new LlmToolCall("read-521", "list_rules", "{}"),
                new LlmToolCall("read-521", "manage_rule", "{}"))));

        assertThrows(BadRequestException.class,
                () -> reads.service.resolveBoardVersion(1L, "owned", ref, "rule", "17"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"preview", "unavailable", "missing-status", "string-available", "error", "confirmation", "mutation", "malformed-version"})
    void rejectsIncompleteOrMalformedCanonicalResults(String invalid) throws Exception {
        String ref = read("list_rules", "rule");
        ObjectNode result = (ObjectNode) reads.mapper.readTree(reads.codec.toolResultContent(reads.lastResult));
        switch (invalid) {
            case "preview" -> result.put("resultStatus", "PREVIEW");
            case "unavailable" -> { result.put("resultStatus", "RESULT_UNAVAILABLE"); result.put("resultAvailable", false); }
            case "missing-status" -> { result.remove("resultStatus"); result.remove("resultAvailable"); }
            case "string-available" -> result.put("resultAvailable", "true");
            case "error" -> result.put("errorCode", "VALIDATION_ERROR");
            case "confirmation" -> result.put("requiresUserConfirmation", true);
            case "mutation" -> result.put("mutationMayHaveCommitted", true);
            default -> ((ObjectNode) result.path("rules").get(0)).put("expectedVersion", "guessed-version");
        }
        reads.lastResult.setContent(reads.codec.serializeToolResult("read-521", result.toString()));
        assertThrows(BadRequestException.class,
                () -> reads.service.resolveBoardVersion(1L, "owned", ref, "rule", "17"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1:abc", "521:rule:0", "521:other:1", "-1:rule:1", "9223372036854775808:rule:1", "521:rule:2147483648"})
    void rejectsMalformedAndOverflowedSelectors(String ref) {
        assertThrows(BadRequestException.class,
                () -> reads.service.resolveBoardVersion(1L, "owned", ref, "rule", "17"));
    }

    @Test
    void aLaterReadNeverSubstitutesForTheSelectedBaseline() throws Exception {
        String ref = read("list_rules", "rule");
        String newerVersion = "v1:" + "b".repeat(64);
        var newer = reads.read(1L, "owned", "list_rules", reads.mapper.writeValueAsString(
                Map.of("rules", List.of(Map.of("ruleId", 17, "expectedVersion", newerVersion)))));
        String newerRef = newer.path("rules").get(0).path("readRef").asText();
        assertNotEquals(ref, newerRef);
        assertEquals(newerVersion, reads.service.resolveBoardVersion(1L, "owned", newerRef, "rule", "17"));
        assertEquals(VERSION, reads.service.resolveBoardVersion(1L, "owned", ref, "rule", "17"));
    }

    private String read(String source, String kind) throws Exception {
        String rows = "rule".equals(kind) ? "rules" : "specs";
        String idField = "rule".equals(kind) ? "ruleId" : "specId";
        Object id = "rule".equals(kind) ? 17L : "17";
        var projected = reads.read(1L, "owned", source, reads.mapper.writeValueAsString(
                Map.of(rows, List.of(Map.of(idField, id, "expectedVersion", VERSION)))));
        return projected.path(rows).get(0).path("readRef").asText();
    }
}
