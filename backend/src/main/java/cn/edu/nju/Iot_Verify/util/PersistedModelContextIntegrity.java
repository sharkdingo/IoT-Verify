package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.dto.model.AttackPointDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelSemanticsDto;
import cn.edu.nju.Iot_Verify.dto.model.SelectedAttackPointDto;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict integrity boundary for persisted verification and simulation model context. */
public final class PersistedModelContextIntegrity {

    private static final Set<ModelSemanticsDto.EnvironmentEvolutionEffect> ENVIRONMENT_EFFECTS = Set.of(
            ModelSemanticsDto.EnvironmentEvolutionEffect
                    .DECLARED_NUMERIC_RATES_AND_DEVICE_EFFECTS_WITHIN_DOMAIN,
            ModelSemanticsDto.EnvironmentEvolutionEffect
                    .UNWRITTEN_DISCRETE_VALUES_NONDETERMINISTIC_WITHIN_DECLARED_DOMAIN,
            ModelSemanticsDto.EnvironmentEvolutionEffect
                    .DEVICE_WRITTEN_DISCRETE_VALUES_HOLD_WHEN_NO_DECLARED_EFFECT_APPLIES);

    private PersistedModelContextIntegrity() {
    }

    public static ValidatedContext readVerification(
            String recordType,
            Object recordId,
            Boolean isAttack,
            Integer attackBudget,
            Boolean enablePrivacy,
            Integer modeledDeviceAttackPointCount,
            Integer modeledFalsifiableReadingDeviceCount,
            Integer modeledAutomationLinkAttackPointCount,
            String modelSemanticsJson,
            String modelSnapshotJson) {
        return read(recordType, recordId, isAttack, attackBudget, enablePrivacy,
                modeledDeviceAttackPointCount, modeledFalsifiableReadingDeviceCount,
                modeledAutomationLinkAttackPointCount, modelSemanticsJson, modelSnapshotJson, true);
    }

    public static ValidatedContext readSimulation(
            String recordType,
            Object recordId,
            Boolean isAttack,
            Integer attackBudget,
            Boolean enablePrivacy,
            Integer modeledDeviceAttackPointCount,
            Integer modeledFalsifiableReadingDeviceCount,
            Integer modeledAutomationLinkAttackPointCount,
            String modelSemanticsJson,
            String modelSnapshotJson) {
        return read(recordType, recordId, isAttack, attackBudget, enablePrivacy,
                modeledDeviceAttackPointCount, modeledFalsifiableReadingDeviceCount,
                modeledAutomationLinkAttackPointCount, modelSemanticsJson, modelSnapshotJson, false);
    }

    private static ValidatedContext read(
            String recordType,
            Object recordId,
            Boolean isAttack,
            Integer attackBudget,
            Boolean enablePrivacy,
            Integer modeledDeviceAttackPointCount,
            Integer modeledFalsifiableReadingDeviceCount,
            Integer modeledAutomationLinkAttackPointCount,
            String modelSemanticsJson,
            String modelSnapshotJson,
            boolean verification) {
        requireBoolean(recordType, recordId, "isAttack", isAttack);
        requireInteger(recordType, recordId, "attackBudget", attackBudget);
        requireBoolean(recordType, recordId, "enablePrivacy", enablePrivacy);
        requireInteger(recordType, recordId,
                "modeledDeviceAttackPointCount", modeledDeviceAttackPointCount);
        requireInteger(recordType, recordId,
                "modeledFalsifiableReadingDeviceCount", modeledFalsifiableReadingDeviceCount);
        requireInteger(recordType, recordId,
                "modeledAutomationLinkAttackPointCount", modeledAutomationLinkAttackPointCount);
        if ((!isAttack && attackBudget != 0) || (isAttack && attackBudget < 1)) {
            throw invalid(recordType, recordId, "attackBudget",
                    "run attack context is inconsistent");
        }

        JsonNode semanticsJson = readJsonObject(
                recordType, recordId, "modelSemanticsJson", modelSemanticsJson);
        requireSemanticsJsonShape(recordType, recordId, semanticsJson);
        ModelSemanticsDto semantics = JsonUtils.readPersistedJsonRequired(
                recordType, recordId, "modelSemanticsJson", modelSemanticsJson,
                () -> JsonUtils.fromJson(modelSemanticsJson, ModelSemanticsDto.class));
        JsonNode snapshotJson = readJsonObject(
                recordType, recordId, "modelSnapshotJson", modelSnapshotJson);
        requireSnapshotJsonShape(recordType, recordId, snapshotJson);
        ModelRunSnapshotDto snapshot = JsonUtils.readPersistedJsonRequired(
                recordType, recordId, "modelSnapshotJson", modelSnapshotJson,
                () -> JsonUtils.fromJson(modelSnapshotJson, ModelRunSnapshotDto.class));

        requireSemantics(recordType, recordId, isAttack, attackBudget, enablePrivacy,
                modeledDeviceAttackPointCount, modeledFalsifiableReadingDeviceCount,
                modeledAutomationLinkAttackPointCount, semantics);
        requireSnapshot(recordType, recordId, snapshot, verification);
        if (modeledDeviceAttackPointCount > snapshot.getDeviceCount()
                || modeledAutomationLinkAttackPointCount != snapshot.getRuleCount()) {
            throw invalid(recordType, recordId, "modelSemanticsJson",
                    "modeled attack-point counts do not match the model snapshot");
        }
        return new ValidatedContext(isAttack, attackBudget, enablePrivacy, semantics, snapshot);
    }

    private static JsonNode readJsonObject(
            String recordType, Object recordId, String field, String json) {
        JsonNode value = JsonUtils.readPersistedJsonRequired(
                recordType, recordId, field, json,
                () -> JsonUtils.fromJson(json, JsonNode.class));
        if (!value.isObject()) {
            throw invalid(recordType, recordId, field, "JSON value must be an object");
        }
        return value;
    }

    private static void requireSemanticsJsonShape(
            String recordType, Object recordId, JsonNode semantics) {
        requireJsonText(recordType, recordId, "modelSemanticsJson", semantics,
                "attackPointUnit");
        requireJsonText(recordType, recordId, "modelSemanticsJson", semantics,
                "attackSelectionPolicy");
        JsonNode selectedPoints = requireJsonArray(
                recordType, recordId, "modelSemanticsJson", semantics,
                "selectedAttackPoints");
        for (JsonNode point : selectedPoints) {
            if (!point.isObject()) {
                throw invalidSemantics(recordType, recordId,
                        "selectedAttackPoints entries must be objects");
            }
            requireJsonText(recordType, recordId, "modelSemanticsJson", point, "kind");
            requireNullableJsonText(
                    recordType, recordId, "modelSemanticsJson", point, "deviceId");
            requireNullableJsonLong(
                    recordType, recordId, "modelSemanticsJson", point, "ruleId");
            requireNullableJsonText(
                    recordType, recordId, "modelSemanticsJson", point, "displayLabel");
        }
        requireJsonTextArray(recordType, recordId, semantics, "attackEffects");
        requireJsonInteger(recordType, recordId, "modelSemanticsJson", semantics,
                "modeledDeviceAttackPointCount");
        requireJsonInteger(recordType, recordId, "modelSemanticsJson", semantics,
                "modeledFalsifiableReadingDeviceCount");
        requireJsonInteger(recordType, recordId, "modelSemanticsJson", semantics,
                "modeledAutomationLinkAttackPointCount");
        requireJsonInteger(recordType, recordId, "modelSemanticsJson", semantics,
                "modeledAttackPointCount");
        requireJsonText(recordType, recordId, "modelSemanticsJson", semantics,
                "trustPropagationPolicy");
        requireJsonText(recordType, recordId, "modelSemanticsJson", semantics,
                "privacyPropagationPolicy");
        requireJsonText(recordType, recordId, "modelSemanticsJson", semantics,
                "labelPropagationScope");
        requireJsonTextArray(recordType, recordId, semantics, "environmentEvolutionEffects");
        requireJsonText(recordType, recordId, "modelSemanticsJson", semantics,
                "localVariableFallbackPolicy");
    }

    private static void requireSnapshotJsonShape(
            String recordType, Object recordId, JsonNode snapshot) {
        requireJsonInteger(recordType, recordId, "modelSnapshotJson", snapshot, "deviceCount");
        requireJsonInteger(recordType, recordId, "modelSnapshotJson", snapshot, "ruleCount");
        requireJsonInteger(
                recordType, recordId, "modelSnapshotJson", snapshot, "specificationCount");
        requireJsonInteger(
                recordType, recordId, "modelSnapshotJson", snapshot, "environmentVariableCount");
        requireJsonInteger(
                recordType, recordId, "modelSnapshotJson", snapshot, "deviceTemplateCount");
        requireJsonBoolean(
                recordType, recordId, "modelSnapshotJson", snapshot, "templatesFrozen");
        JsonNode fingerprint = snapshot.get("modelFingerprint");
        if (fingerprint != null && !fingerprint.isNull() && !fingerprint.isTextual()) {
            throw invalid(recordType, recordId, "modelSnapshotJson", "modelFingerprint must be a string or null");
        }
    }

    private static JsonNode requireJsonArray(
            String recordType,
            Object recordId,
            String persistedField,
            JsonNode object,
            String jsonField) {
        JsonNode value = object.get(jsonField);
        if (value == null || !value.isArray()) {
            throw invalid(recordType, recordId, persistedField,
                    "field '" + jsonField + "' must be an array");
        }
        return value;
    }

    private static void requireJsonTextArray(
            String recordType, Object recordId, JsonNode object, String jsonField) {
        JsonNode values = requireJsonArray(
                recordType, recordId, "modelSemanticsJson", object, jsonField);
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw invalidSemantics(recordType, recordId,
                        "field '" + jsonField + "' must contain only strings");
            }
        }
    }

    private static void requireJsonText(
            String recordType,
            Object recordId,
            String persistedField,
            JsonNode object,
            String jsonField) {
        JsonNode value = object.get(jsonField);
        if (value == null || !value.isTextual()) {
            throw invalid(recordType, recordId, persistedField,
                    "field '" + jsonField + "' must be a string");
        }
    }

    private static void requireNullableJsonText(
            String recordType,
            Object recordId,
            String persistedField,
            JsonNode object,
            String jsonField) {
        JsonNode value = requireJsonField(
                recordType, recordId, persistedField, object, jsonField);
        if (!value.isNull() && !value.isTextual()) {
            throw invalid(recordType, recordId, persistedField,
                    "field '" + jsonField + "' must be null or a string");
        }
    }

    private static void requireJsonInteger(
            String recordType,
            Object recordId,
            String persistedField,
            JsonNode object,
            String jsonField) {
        JsonNode value = object.get(jsonField);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(recordType, recordId, persistedField,
                    "field '" + jsonField + "' must be an integer");
        }
    }

    private static void requireNullableJsonLong(
            String recordType,
            Object recordId,
            String persistedField,
            JsonNode object,
            String jsonField) {
        JsonNode value = requireJsonField(
                recordType, recordId, persistedField, object, jsonField);
        if (!value.isNull() && (!value.isIntegralNumber() || !value.canConvertToLong())) {
            throw invalid(recordType, recordId, persistedField,
                    "field '" + jsonField + "' must be null or an integer");
        }
    }

    private static void requireJsonBoolean(
            String recordType,
            Object recordId,
            String persistedField,
            JsonNode object,
            String jsonField) {
        JsonNode value = object.get(jsonField);
        if (value == null || !value.isBoolean()) {
            throw invalid(recordType, recordId, persistedField,
                    "field '" + jsonField + "' must be a boolean");
        }
    }

    private static JsonNode requireJsonField(
            String recordType,
            Object recordId,
            String persistedField,
            JsonNode object,
            String jsonField) {
        if (!object.has(jsonField)) {
            throw invalid(recordType, recordId, persistedField,
                    "required field '" + jsonField + "' is missing");
        }
        return object.get(jsonField);
    }

    private static void requireSemantics(
            String recordType,
            Object recordId,
            boolean isAttack,
            int attackBudget,
            boolean enablePrivacy,
            int modeledDeviceAttackPointCount,
            int modeledFalsifiableReadingDeviceCount,
            int modeledAutomationLinkAttackPointCount,
            ModelSemanticsDto semantics) {
        if (semantics.getAttackPointUnit()
                != ModelSemanticsDto.AttackPointUnit
                .BEHAVIOR_CHANGING_DEVICE_INSTANCE_OR_AUTOMATION_LINK
                || semantics.getAttackSelectionPolicy() == null
                || semantics.getSelectedAttackPoints() == null
                || semantics.getAttackEffects() == null
                || semantics.getTrustPropagationPolicy()
                != ModelSemanticsDto.TrustPropagationPolicy
                .TARGET_UNTRUSTED_IF_ALL_TRIGGER_SOURCES_UNTRUSTED
                || semantics.getPrivacyPropagationPolicy() == null
                || semantics.getLabelPropagationScope()
                != ModelSemanticsDto.LabelPropagationScope.AUTOMATION_RULE_COMMANDS_ONLY
                || semantics.getEnvironmentEvolutionEffects() == null
                || semantics.getLocalVariableFallbackPolicy()
                != ModelSemanticsDto.LocalVariableFallbackPolicy.STUTTER_WHEN_NO_DECLARED_EVOLUTION) {
            throw invalidSemantics(recordType, recordId, "required semantic fields are missing");
        }
        if (semantics.getModeledDeviceAttackPointCount() < 0
                || semantics.getModeledFalsifiableReadingDeviceCount() < 0
                || semantics.getModeledAutomationLinkAttackPointCount() < 0
                || semantics.getModeledAttackPointCount() < 0
                || semantics.getModeledAttackPointCount()
                != semantics.getModeledDeviceAttackPointCount()
                + semantics.getModeledAutomationLinkAttackPointCount()
                || semantics.getModeledFalsifiableReadingDeviceCount()
                > semantics.getModeledDeviceAttackPointCount()) {
            throw invalidSemantics(recordType, recordId, "modeled attack-point counts are inconsistent");
        }
        if (modeledDeviceAttackPointCount != semantics.getModeledDeviceAttackPointCount()
                || modeledFalsifiableReadingDeviceCount
                != semantics.getModeledFalsifiableReadingDeviceCount()
                || modeledAutomationLinkAttackPointCount
                != semantics.getModeledAutomationLinkAttackPointCount()) {
            throw invalidSemantics(recordType, recordId,
                    "semantic counts do not match persisted run columns");
        }
        ModelSemanticsDto.PrivacyPropagationPolicy expectedPrivacy = enablePrivacy
                ? ModelSemanticsDto.PrivacyPropagationPolicy
                .TARGET_PRIVATE_IF_ANY_TRIGGER_OR_SELECTED_CONTENT_PRIVATE
                : ModelSemanticsDto.PrivacyPropagationPolicy.NOT_MODELED;
        if (semantics.getPrivacyPropagationPolicy() != expectedPrivacy) {
            throw invalidSemantics(recordType, recordId,
                    "privacy semantics do not match enablePrivacy");
        }
        requireExactSet(recordType, recordId, semantics.getEnvironmentEvolutionEffects(),
                ENVIRONMENT_EFFECTS, "environment evolution effects are incomplete or duplicated");
        requireAttackSemantics(recordType, recordId, isAttack, attackBudget, semantics);
    }

    private static void requireAttackSemantics(
            String recordType,
            Object recordId,
            boolean isAttack,
            int attackBudget,
            ModelSemanticsDto semantics) {
        List<SelectedAttackPointDto> selectedPoints = semantics.getSelectedAttackPoints();
        List<ModelSemanticsDto.AttackEffect> attackEffects = semantics.getAttackEffects();
        if (attackEffects.stream().anyMatch(effect -> effect == null)
                || new HashSet<>(attackEffects).size() != attackEffects.size()) {
            throw invalidSemantics(recordType, recordId, "attack effects are invalid or duplicated");
        }
        Set<ModelSemanticsDto.AttackEffect> effects = Set.copyOf(attackEffects);
        if (!isAttack) {
            if (semantics.getAttackSelectionPolicy()
                    != ModelSemanticsDto.AttackSelectionPolicy.NOT_MODELED
                    || !selectedPoints.isEmpty() || !effects.isEmpty()) {
                throw invalidSemantics(recordType, recordId,
                        "non-attack run contains attack semantics");
            }
            return;
        }
        if (semantics.getAttackSelectionPolicy()
                == ModelSemanticsDto.AttackSelectionPolicy.NOT_MODELED
                || attackBudget > semantics.getModeledAttackPointCount()) {
            throw invalidSemantics(recordType, recordId,
                    "attack selection does not match the persisted budget");
        }
        if (effects.isEmpty()) {
            throw invalidSemantics(recordType, recordId,
                    "attack-enabled run has no modeled attack effect");
        }

        int selectedDeviceCount = 0;
        int selectedLinkCount = 0;
        Set<String> identities = new HashSet<>();
        if (semantics.getAttackSelectionPolicy()
                == ModelSemanticsDto.AttackSelectionPolicy.EXACT_ATTACK_POINTS) {
            if (selectedPoints.size() != attackBudget) {
                throw invalidSemantics(recordType, recordId,
                        "exact attack points do not match the persisted budget");
            }
            for (SelectedAttackPointDto point : selectedPoints) {
                if (point == null || point.getKind() == null
                        || (point.getDisplayLabel() != null
                        && (!hasCanonicalText(point.getDisplayLabel())))) {
                    throw invalidSemantics(recordType, recordId, "selected attack point is invalid");
                }
                String identity;
                if (point.getKind() == AttackPointDto.Kind.DEVICE) {
                    if (!hasCanonicalText(point.getDeviceId()) || point.getRuleId() != null) {
                        throw invalidSemantics(recordType, recordId,
                                "selected device attack point is invalid");
                    }
                    identity = "DEVICE:" + point.getDeviceId();
                    selectedDeviceCount++;
                } else if (point.getKind() == AttackPointDto.Kind.AUTOMATION_LINK) {
                    if (point.getDeviceId() != null || point.getRuleId() == null
                            || point.getRuleId() < 1) {
                        throw invalidSemantics(recordType, recordId,
                                "selected automation-link attack point is invalid");
                    }
                    identity = "AUTOMATION_LINK:" + point.getRuleId();
                    selectedLinkCount++;
                } else {
                    throw invalidSemantics(recordType, recordId, "selected attack point is invalid");
                }
                if (!identities.add(identity)) {
                    throw invalidSemantics(recordType, recordId,
                            "selected attack points contain duplicates");
                }
            }
            if (selectedDeviceCount > semantics.getModeledDeviceAttackPointCount()
                    || selectedLinkCount > semantics.getModeledAutomationLinkAttackPointCount()) {
                throw invalidSemantics(recordType, recordId,
                        "selected attack points exceed the modeled attack surface");
            }
        } else if (semantics.getAttackSelectionPolicy()
                == ModelSemanticsDto.AttackSelectionPolicy.UP_TO_ATTACK_BUDGET_NONDETERMINISTIC) {
            if (!selectedPoints.isEmpty()) {
                throw invalidSemantics(recordType, recordId,
                        "budget attack run cannot contain exact attack points");
            }
            requireExhaustiveEffects(recordType, recordId, semantics, effects);
        } else {
            throw invalidSemantics(recordType, recordId, "attack selection policy is invalid");
        }

        requireEffectCounts(recordType, recordId, semantics, effects);
        if (selectedLinkCount > 0 && !effects.contains(
                ModelSemanticsDto.AttackEffect.COMMAND_ON_COMPROMISED_AUTOMATION_LINK_IS_DROPPED)) {
            throw invalidSemantics(recordType, recordId,
                    "selected automation link has no modeled attack effect");
        }
    }

    private static void requireExhaustiveEffects(
            String recordType,
            Object recordId,
            ModelSemanticsDto semantics,
            Set<ModelSemanticsDto.AttackEffect> effects) {
        if (semantics.getModeledFalsifiableReadingDeviceCount() > 0
                && !effects.contains(ModelSemanticsDto.AttackEffect
                .DECLARED_FALSIFIABLE_READING_NONDETERMINISTIC_WITHIN_DECLARED_DOMAIN)) {
            throw invalidSemantics(recordType, recordId,
                    "budget attack omits the modeled falsifiable-reading effect");
        }
        if (semantics.getModeledAutomationLinkAttackPointCount() > 0
                && (!effects.contains(ModelSemanticsDto.AttackEffect
                .COMMAND_TO_COMPROMISED_TARGET_IS_DROPPED)
                || !effects.contains(ModelSemanticsDto.AttackEffect
                .COMMAND_ON_COMPROMISED_AUTOMATION_LINK_IS_DROPPED))) {
            throw invalidSemantics(recordType, recordId,
                    "budget attack omits a modeled command-drop effect");
        }
    }

    private static void requireEffectCounts(
            String recordType,
            Object recordId,
            ModelSemanticsDto semantics,
            Set<ModelSemanticsDto.AttackEffect> effects) {
        if (effects.contains(ModelSemanticsDto.AttackEffect
                .DECLARED_FALSIFIABLE_READING_NONDETERMINISTIC_WITHIN_DECLARED_DOMAIN)
                && semantics.getModeledFalsifiableReadingDeviceCount() < 1) {
            throw invalidSemantics(recordType, recordId,
                    "falsifiable-reading effect has no modeled device");
        }
        if (effects.contains(ModelSemanticsDto.AttackEffect.COMMAND_TO_COMPROMISED_TARGET_IS_DROPPED)
                && (semantics.getModeledDeviceAttackPointCount() < 1
                || semantics.getModeledAutomationLinkAttackPointCount() < 1)) {
            throw invalidSemantics(recordType, recordId,
                    "compromised-target effect has no modeled command path");
        }
        if (effects.contains(ModelSemanticsDto.AttackEffect
                .COMMAND_ON_COMPROMISED_AUTOMATION_LINK_IS_DROPPED)
                && semantics.getModeledAutomationLinkAttackPointCount() < 1) {
            throw invalidSemantics(recordType, recordId,
                    "automation-link effect has no modeled link");
        }
    }

    private static void requireSnapshot(
            String recordType,
            Object recordId,
            ModelRunSnapshotDto snapshot,
            boolean verification) {
        boolean invalidCounts = snapshot.getDeviceCount() < 1
                || snapshot.getRuleCount() < 0
                || snapshot.getSpecificationCount() < 0
                || snapshot.getEnvironmentVariableCount() < 0
                || snapshot.getDeviceTemplateCount() < 1
                || snapshot.getDeviceTemplateCount() > snapshot.getDeviceCount();
        boolean invalidSpecificationCount = verification
                ? snapshot.getSpecificationCount() < 1
                : snapshot.getSpecificationCount() != 0;
        if (snapshot.getCapturedAt() == null || !snapshot.isTemplatesFrozen()
                || (snapshot.getModelFingerprint() != null
                && !snapshot.getModelFingerprint().matches("[0-9a-f]{64}"))
                || invalidCounts || invalidSpecificationCount) {
            throw invalid(recordType, recordId, "modelSnapshotJson",
                    "model snapshot fields are invalid");
        }
    }

    private static void requireBoolean(
            String recordType, Object recordId, String field, Boolean value) {
        if (value == null) {
            throw invalid(recordType, recordId, field, "required run context is missing");
        }
    }

    private static void requireInteger(
            String recordType, Object recordId, String field, Integer value) {
        if (value == null) {
            throw invalid(recordType, recordId, field, "required run context is missing");
        }
        if (value < 0) {
            throw invalid(recordType, recordId, field, "run context count cannot be negative");
        }
    }

    private static <T> void requireExactSet(
            String recordType,
            Object recordId,
            List<T> values,
            Set<T> expected,
            String reason) {
        if (values.stream().anyMatch(value -> value == null)
                || values.size() != expected.size()
                || !new HashSet<>(values).equals(expected)) {
            throw invalidSemantics(recordType, recordId, reason);
        }
    }

    private static boolean hasCanonicalText(String value) {
        return value != null && !value.isBlank() && value.equals(value.trim());
    }

    private static PersistedDataIntegrityException invalidSemantics(
            String recordType, Object recordId, String reason) {
        return invalid(recordType, recordId, "modelSemanticsJson", reason);
    }

    private static PersistedDataIntegrityException invalid(
            String recordType, Object recordId, String field, String reason) {
        return new PersistedDataIntegrityException(recordType, recordId, field, reason);
    }

    public record ValidatedContext(
            boolean isAttack,
            int attackBudget,
            boolean enablePrivacy,
            ModelSemanticsDto modelSemantics,
            ModelRunSnapshotDto modelSnapshot) {
    }
}
