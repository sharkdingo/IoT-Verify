package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.dto.model.AttackPointDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.model.EnvironmentValueProvenanceDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelSemanticsDto;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistedModelContextIntegrityTest {

    private static final ModelSemanticsDto NO_ATTACK_SEMANTICS = ModelSemanticsDto.forRun(
            AttackScenarioDto.none(), false, 1, 0, 0);
    private static final ModelRunSnapshotDto VERIFICATION_SNAPSHOT = ModelRunSnapshotDto.captured(
            LocalDateTime.of(2026, 7, 24, 9, 30), 1, 0, 1, 0, 1);

    @Test
    void acceptsCurrentCanonicalModelContext() {
        PersistedModelContextIntegrity.ValidatedContext context = readVerification(
                JsonUtils.toJson(NO_ATTACK_SEMANTICS), JsonUtils.toJson(VERIFICATION_SNAPSHOT));

        assertEquals(1, context.modelSemantics().getModeledDeviceAttackPointCount());
        assertEquals(1, context.modelSnapshot().getDeviceCount());
    }

    @Test
    void restoresValidComparisonEvidenceAndRejectsMalformedFingerprints() {
        var captured = VERIFICATION_SNAPSHOT.toBuilder().modelFingerprint("a".repeat(64)).build();
        assertEquals("a".repeat(64), readVerification(JsonUtils.toJson(NO_ATTACK_SEMANTICS),
                JsonUtils.toJson(captured)).modelSnapshot().getModelFingerprint());
        for (String malformed : List.of("", "a".repeat(63), "A".repeat(64), "g".repeat(64))) {
            captured.setModelFingerprint(malformed);
            assertThrows(PersistedDataIntegrityException.class, () -> readVerification(
                    JsonUtils.toJson(NO_ATTACK_SEMANTICS), JsonUtils.toJson(captured)));
        }
        ObjectNode malformed = (ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().valueToTree(VERIFICATION_SNAPSHOT);
        malformed.put("modelFingerprint", new java.math.BigInteger("1".repeat(64)));
        assertThrows(PersistedDataIntegrityException.class, () -> readVerification(
                JsonUtils.toJson(NO_ATTACK_SEMANTICS), malformed.toString()));
    }

    @Test
    void restoresPerValueProvenanceSoAStoredTraceStaysExplainable() {
        // Provenance is the only record of why a value was allowed to move. If it did not survive
        // persistence, explaining a stored trace would fall back to the current Board -- which may
        // have changed since the run, so the explanation could contradict the trace.
        ModelRunSnapshotDto captured = VERIFICATION_SNAPSHOT.toBuilder()
                .environmentProvenance(List.of(EnvironmentValueProvenanceDto.builder()
                        .name("weather")
                        .type(EnvironmentValueProvenanceDto.ValueType.DISCRETE_ENUM)
                        .values(List.of("sunny", "rainy"))
                        .authorship(EnvironmentValueProvenanceDto.AuthorshipCategory.EXOGENOUS)
                        .semantics(EnvironmentValueProvenanceDto.SemanticsTag.ABSTRACTION)
                        .writers(List.of())
                        .readers(List.of(EnvironmentValueProvenanceDto.DeviceReader.builder()
                                .deviceVarName("weather_1").build()))
                        .evolutionSummary("External input.")
                        .build()))
                .build();

        PersistedModelContextIntegrity.ValidatedContext context = readVerification(
                JsonUtils.toJson(NO_ATTACK_SEMANTICS), JsonUtils.toJson(captured));

        List<EnvironmentValueProvenanceDto> restored =
                context.modelSnapshot().getEnvironmentProvenance();
        assertEquals(1, restored.size(), "provenance must survive the persisted JSON round trip");
        EnvironmentValueProvenanceDto weather = restored.get(0);
        assertEquals("weather", weather.getName());
        // The abstraction tag is the part a user acts on, so losing it silently would be worse than
        // losing the whole entry: the trace would look exact.
        assertEquals(EnvironmentValueProvenanceDto.SemanticsTag.ABSTRACTION, weather.getSemantics());
        assertEquals(EnvironmentValueProvenanceDto.AuthorshipCategory.EXOGENOUS,
                weather.getAuthorship());
        assertEquals(List.of("sunny", "rainy"), weather.getValues());
        assertEquals("weather_1", weather.getReaders().get(0).getDeviceVarName());
    }

    @Test
    void acceptsARunThatHadNoSharedValues() {
        // A board of purely device-local variables produces no provenance. That is an ordinary run,
        // not corrupt persisted data, so it must not be rejected.
        PersistedModelContextIntegrity.ValidatedContext context = readVerification(
                JsonUtils.toJson(NO_ATTACK_SEMANTICS),
                JsonUtils.toJson(VERIFICATION_SNAPSHOT.toBuilder()
                        .environmentProvenance(List.of()).build()));

        assertTrue(context.modelSnapshot().getEnvironmentProvenance().isEmpty());
    }

    @Test
    void rejectsMissingPrimitiveSemanticFieldsInsteadOfDefaultingThem() {
        ObjectNode canonical = object(JsonUtils.toJson(NO_ATTACK_SEMANTICS));
        for (String field : List.of(
                "modeledDeviceAttackPointCount",
                "modeledFalsifiableReadingDeviceCount",
                "modeledAutomationLinkAttackPointCount",
                "modeledAttackPointCount")) {
            ObjectNode damaged = canonical.deepCopy();
            damaged.remove(field);

            assertInvalid(
                    JsonUtils.toJson(damaged), JsonUtils.toJson(VERIFICATION_SNAPSHOT),
                    "modelSemanticsJson", field);
        }
    }

    @Test
    void rejectsMissingPrimitiveSnapshotFieldsInsteadOfDefaultingThem() {
        ObjectNode canonical = object(JsonUtils.toJson(VERIFICATION_SNAPSHOT));
        for (String field : List.of(
                "deviceCount",
                "ruleCount",
                "specificationCount",
                "environmentVariableCount",
                "deviceTemplateCount",
                "templatesFrozen")) {
            ObjectNode damaged = canonical.deepCopy();
            damaged.remove(field);

            assertInvalid(
                    JsonUtils.toJson(NO_ATTACK_SEMANTICS), JsonUtils.toJson(damaged),
                    "modelSnapshotJson", field);
        }
    }

    @Test
    void rejectsStringAndDecimalCoercionForIntegerFields() {
        ObjectNode semantics = object(JsonUtils.toJson(NO_ATTACK_SEMANTICS));
        semantics.put("modeledFalsifiableReadingDeviceCount", "0");
        assertInvalid(
                JsonUtils.toJson(semantics), JsonUtils.toJson(VERIFICATION_SNAPSHOT),
                "modelSemanticsJson", "modeledFalsifiableReadingDeviceCount");

        ObjectNode snapshot = object(JsonUtils.toJson(VERIFICATION_SNAPSHOT));
        snapshot.put("ruleCount", "0");
        assertInvalid(
                JsonUtils.toJson(NO_ATTACK_SEMANTICS), JsonUtils.toJson(snapshot),
                "modelSnapshotJson", "ruleCount");

        snapshot = object(JsonUtils.toJson(VERIFICATION_SNAPSHOT));
        snapshot.put("ruleCount", 0.0);
        assertInvalid(
                JsonUtils.toJson(NO_ATTACK_SEMANTICS), JsonUtils.toJson(snapshot),
                "modelSnapshotJson", "ruleCount");
    }

    @Test
    void rejectsStringCoercionForBooleanFields() {
        ObjectNode snapshot = object(JsonUtils.toJson(VERIFICATION_SNAPSHOT));
        snapshot.put("templatesFrozen", "true");

        assertInvalid(
                JsonUtils.toJson(NO_ATTACK_SEMANTICS), JsonUtils.toJson(snapshot),
                "modelSnapshotJson", "templatesFrozen");
    }

    @Test
    void rejectsOrdinalCoercionForSemanticEnumsAndEnumLists() {
        ObjectNode semantics = object(JsonUtils.toJson(NO_ATTACK_SEMANTICS));
        semantics.put("attackSelectionPolicy", 0);
        assertInvalid(
                JsonUtils.toJson(semantics), JsonUtils.toJson(VERIFICATION_SNAPSHOT),
                "modelSemanticsJson", "attackSelectionPolicy");

        semantics = object(JsonUtils.toJson(NO_ATTACK_SEMANTICS));
        semantics.withArray("environmentEvolutionEffects").set(0,
                com.fasterxml.jackson.databind.node.IntNode.valueOf(0));
        assertInvalid(
                JsonUtils.toJson(semantics), JsonUtils.toJson(VERIFICATION_SNAPSHOT),
                "modelSemanticsJson", "environmentEvolutionEffects");
    }

    @Test
    void rejectsScalarCoercionInsideSelectedAttackPoints() {
        ModelSemanticsDto semantics = ModelSemanticsDto.forRun(
                AttackScenarioDto.exactPoints(List.of(AttackPointDto.automationLink(7L))),
                false, 1, 1, 0);
        ModelRunSnapshotDto snapshot = ModelRunSnapshotDto.captured(
                LocalDateTime.of(2026, 7, 24, 9, 30), 1, 1, 1, 0, 1);
        ObjectNode semanticsJson = object(JsonUtils.toJson(semantics));
        ((ObjectNode) semanticsJson.withArray("selectedAttackPoints").get(0))
                .put("ruleId", "7");

        PersistedDataIntegrityException error = assertThrows(
                PersistedDataIntegrityException.class,
                () -> PersistedModelContextIntegrity.readVerification(
                        "verification task", 19L, true, 1, false,
                        1, 0, 1, JsonUtils.toJson(semanticsJson), JsonUtils.toJson(snapshot)));

        assertEquals("modelSemanticsJson", error.getField());
        assertTrue(error.getMessage().contains("ruleId"));
    }

    private PersistedModelContextIntegrity.ValidatedContext readVerification(
            String semanticsJson, String snapshotJson) {
        return PersistedModelContextIntegrity.readVerification(
                "verification task", 17L, false, 0, false,
                1, 0, 0, semanticsJson, snapshotJson);
    }

    private void assertInvalid(
            String semanticsJson,
            String snapshotJson,
            String expectedPersistedField,
            String expectedJsonField) {
        PersistedDataIntegrityException error = assertThrows(
                PersistedDataIntegrityException.class,
                () -> readVerification(semanticsJson, snapshotJson));
        assertEquals(expectedPersistedField, error.getField());
        assertTrue(error.getMessage().contains(expectedJsonField));
    }

    private ObjectNode object(String json) {
        return JsonUtils.fromJson(json, ObjectNode.class);
    }
}
