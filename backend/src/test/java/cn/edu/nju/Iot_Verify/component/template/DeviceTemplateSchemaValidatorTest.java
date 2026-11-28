package cn.edu.nju.Iot_Verify.component.template;

import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceTemplateSchemaValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DeviceTemplateSchemaValidator validator = new DeviceTemplateSchemaValidator(objectMapper);

    @Test
    void defaultTemplates_matchCanonicalSchema() throws Exception {
        Path templateDir = Path.of("src/main/resources/deviceTemplate");
        List<Path> templates;
        try (Stream<Path> stream = Files.list(templateDir)) {
            templates = stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }

        assertFalse(templates.isEmpty(), "default templates should exist");
        for (Path template : templates) {
            JsonNode manifest = objectMapper.readTree(template.toFile());
            String name = manifest.path("Name").asText(template.getFileName().toString());
            assertDoesNotThrow(
                    () -> validator.validateRawManifest(name, manifest),
                    () -> "Template should match backend/device-template-schema.json: " + template);
        }
    }

    @Test
    void defaultControlSourceLabels_distinguishExternalInputsFromInHouseAction() throws Exception {
        JsonNode clock = objectMapper.readTree(
                Path.of("src/main/resources/deviceTemplate/Clock.json").toFile());
        JsonNode calendar = objectMapper.readTree(
                Path.of("src/main/resources/deviceTemplate/Calendar.json").toFile());
        JsonNode motion = objectMapper.readTree(
                Path.of("src/main/resources/deviceTemplate/Motion Detector.json").toFile());

        assertEquals("untrusted", clock.path("InternalVariables").get(0).path("Trust").asText());
        assertEquals("untrusted", calendar.path("InternalVariables").get(0).path("Trust").asText());
        assertEquals("untrusted", calendar.path("InternalVariables").get(1).path("Trust").asText());
        assertEquals("trusted", motion.path("InternalVariables").get(0).path("Trust").asText());

        assertVariableTrust("Car", "location", "untrusted");
        assertVariableTrust("Door RFID", "RFID", "untrusted");
        assertVariableTrust("Email", "receiveKey", "untrusted");
        assertVariableTrust("Email", "receiveMail", "untrusted");
        assertVariableTrust("Garage Door", "contact", "untrusted");
        assertVariableTrust("Mobile Phone", "location", "untrusted");
        assertVariableTrust("Mobile Phone", "steps", "untrusted");
        assertVariableTrust("Window", "contact", "untrusted");
    }

    @Test
    void defaultContentCapabilities_areDeclaredOnlyOnContentCarryingActions() throws Exception {
        JsonNode email = objectMapper.readTree(
                Path.of("src/main/resources/deviceTemplate/Email.json").toFile());
        JsonNode twitter = objectMapper.readTree(
                Path.of("src/main/resources/deviceTemplate/Twitter.json").toFile());
        JsonNode light = objectMapper.readTree(
                Path.of("src/main/resources/deviceTemplate/Light.json").toFile());

        assertEquals(true, email.path("APIs").get(0).path("AcceptsContent").asBoolean());
        assertEquals(true, twitter.path("APIs").get(0).path("AcceptsContent").asBoolean());
        assertFalse(light.path("APIs").get(0).path("AcceptsContent").asBoolean(false));
    }

    @Test
    void unknownFields_areRejectedByCanonicalSchema() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Debug Sensor",
                  "Unexpected": true
                }
                """);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> validator.validateRawManifest("Debug Sensor", manifest));

        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                .contains("backend/device-template-schema.json")
                .contains("Unexpected");
    }

    /**
     * A content name is an SMV identifier, and used to be the only one nothing checked.
     *
     * <p>`SmvDeviceModuleBuilder` concatenates it verbatim into `privacy_<name>`, exactly as it does for
     * an InternalVariable or an ImpactedVariable — but those two carry this pattern on both the schema and
     * the Java side, and `Contents.Name` carried it on neither. Measured before the fix: a content named
     * `my photo` emitted `privacy_my photo: {public, private};` and NuSMV refused the whole model with
     * `at token "photo": syntax error`. That turned an import-time rejection into a run-time generation
     * failure on a template the user had already saved.
     */
    @Test
    void contentNameMustBeAnSmvIdentifierLikeEveryOtherEmittedName() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Leaky Camera",
                  "Modes": ["MachineState"],
                  "InitState": "idle",
                  "WorkingStates": [
                    {"Name": "idle", "Trust": "trusted", "Privacy": "public"},
                    {"Name": "sending", "Trust": "trusted", "Privacy": "private"}
                  ],
                  "Contents": [{"Name": "my photo", "Privacy": "private"}],
                  "APIs": [
                    {"Name": "send", "StartState": "idle", "EndState": "sending", "Signal": true}
                  ]
                }
                """);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> validator.validateRawManifest("Leaky Camera", manifest));

        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                .contains("Contents")
                .contains("Name");
    }

    /**
     * Every bundled template must satisfy the Java-side validator too, not only the schema.
     *
     * <p>First-login seeding (`DeviceTemplateServiceImpl.loadDefaultTemplateEntities`) runs
     * `validateRawManifest` and stops there, while the three user-facing writers all reach
     * `validateTemplateManifestForNuSmv` through `addDeviceTemplate`. So every check that lives only on
     * the Java side — generated-identifier collisions, state-name tokens, and the enum-value tokens
     * added alongside this test — does **not** run on the bundled files.
     *
     * <p>All 45 pass today, so the gap has no consequence. But that is luck rather than design: nothing
     * stopped a bundled template from being authored past a gate the seeding path never applies, and the
     * failure would surface as an engine error on a template the user never edited. This test converts the
     * accident into an invariant without touching the seeding path, whose cycle
     * (`DeviceTemplateServiceImpl` → `DeviceTemplateNuSmvValidator` → `SmvGenerator` →
     * `DeviceSmvDataFactory` → `DeviceTemplateService`) makes injecting the validator a design change
     * rather than a fix.
     */
    @Test
    void everyBundledTemplatePassesTheJavaSideValidatorTheSeedingPathSkips() throws Exception {
        DeviceTemplateNuSmvValidator nuSmvValidator = new DeviceTemplateNuSmvValidator(
                org.mockito.Mockito.mock(
                        cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator.class));
        java.nio.file.Path dir = java.nio.file.Path.of("src", "main", "resources", "deviceTemplate");
        java.util.List<String> rejected = new java.util.ArrayList<>();
        int checked = 0;
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path file : files
                    .filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .toList()) {
                String name = file.getFileName().toString().replace(".json", "");
                DeviceTemplateDto.DeviceManifest manifest = objectMapper.readValue(
                        java.nio.file.Files.readString(file), DeviceTemplateDto.DeviceManifest.class);
                try {
                    nuSmvValidator.validateTemplateManifestForNuSmv(name, manifest);
                    checked++;
                } catch (RuntimeException rejection) {
                    rejected.add(name + ": " + rejection.getMessage());
                }
            }
        }
        org.assertj.core.api.Assertions.assertThat(rejected).isEmpty();
        org.assertj.core.api.Assertions.assertThat(checked)
                .as("the scan must actually find the bundled templates, not silently check nothing")
                .isGreaterThanOrEqualTo(40);
    }

    /** The same manifest with a legal content name must still be accepted. */
    @Test
    void contentNameThatIsAnSmvIdentifierIsAccepted() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Camera",
                  "Modes": ["MachineState"],
                  "InitState": "idle",
                  "WorkingStates": [
                    {"Name": "idle", "Trust": "trusted", "Privacy": "public"},
                    {"Name": "sending", "Trust": "trusted", "Privacy": "private"}
                  ],
                  "Contents": [{"Name": "photo", "Privacy": "private"}],
                  "APIs": [
                    {"Name": "send", "StartState": "idle", "EndState": "sending", "Signal": true}
                  ]
                }
                """);

        assertDoesNotThrow(() -> validator.validateRawManifest("Camera", manifest));
    }

    @Test
    void securityLabelsRejectUppercaseInsteadOfSilentlyNormalizing() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Security Labels",
                  "Modes": ["Power"],
                  "InitState": "off",
                  "WorkingStates": [{
                    "Name": "off",
                    "Trust": "trusted",
                    "Privacy": "public"
                  }],
                  "InternalVariables": [{
                    "Name": "reading",
                    "IsInside": true,
                    "FalsifiableWhenCompromised": false,
                    "Trust": "trusted",
                    "Privacy": "public",
                    "Values": ["ready", "busy"]
                  }],
                  "Contents": [{"Name": "status", "Privacy": "public"}]
                }
                """);
        assertDoesNotThrow(() -> validator.validateRawManifest("Security Labels", manifest));

        JsonNode uppercaseStateTrust = manifest.deepCopy();
        ((ObjectNode) uppercaseStateTrust.path("WorkingStates").get(0)).put("Trust", "Trusted");
        JsonNode uppercaseStatePrivacy = manifest.deepCopy();
        ((ObjectNode) uppercaseStatePrivacy.path("WorkingStates").get(0)).put("Privacy", "Public");
        JsonNode uppercaseVariableTrust = manifest.deepCopy();
        ((ObjectNode) uppercaseVariableTrust.path("InternalVariables").get(0)).put("Trust", "Untrusted");
        JsonNode uppercaseVariablePrivacy = manifest.deepCopy();
        ((ObjectNode) uppercaseVariablePrivacy.path("InternalVariables").get(0)).put("Privacy", "Private");
        JsonNode uppercaseContentPrivacy = manifest.deepCopy();
        ((ObjectNode) uppercaseContentPrivacy.path("Contents").get(0)).put("Privacy", "Private");

        for (JsonNode uppercaseManifest : List.of(
                uppercaseStateTrust,
                uppercaseStatePrivacy,
                uppercaseVariableTrust,
                uppercaseVariablePrivacy,
                uppercaseContentPrivacy)) {
            BadRequestException exception = assertThrows(BadRequestException.class,
                    () -> validator.validateRawManifest("Security Labels", uppercaseManifest));
            org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                    .contains("backend/device-template-schema.json");
        }
    }

    @Test
    void internalVariableScopeMustBeExplicitInsteadOfDefaultingToSharedEnvironment() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Ambiguous Sensor",
                  "InternalVariables": [{
                    "Name": "reading",
                    "FalsifiableWhenCompromised": true,
                    "Trust": "trusted",
                    "Privacy": "public",
                    "LowerBound": 0,
                    "UpperBound": 100
                  }]
                }
                """);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> validator.validateRawManifest("Ambiguous Sensor", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("IsInside");
    }

    @Test
    void internalVariableDomainMustBeExplicitInsteadOfDefaultingToBoolean() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Implicit Boolean Sensor",
                  "InternalVariables": [{
                    "Name": "detected",
                    "IsInside": true,
                    "FalsifiableWhenCompromised": true,
                    "Trust": "trusted",
                    "Privacy": "public"
                  }]
                }
                """);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> validator.validateRawManifest("Implicit Boolean Sensor", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("Values")
                .contains("LowerBound");
    }

    @Test
    void initialStateMustBeOneConcreteWorkingState() throws Exception {
        JsonNode wildcard = objectMapper.readTree("""
                {
                  "Name": "Ambiguous Mode Device",
                  "Modes": ["Power", "Profile"],
                  "InitState": "on;_",
                  "WorkingStates": [
                    {"Name": "on;normal", "Trust": "trusted", "Privacy": "public"},
                    {"Name": "off;normal", "Trust": "trusted", "Privacy": "public"}
                  ]
                }
                """);
        BadRequestException wildcardException = assertThrows(BadRequestException.class,
                () -> validator.validateRawManifest("Ambiguous Mode Device", wildcard));
        org.assertj.core.api.Assertions.assertThat(wildcardException.getMessage())
                .contains("InitState 'on;_'")
                .contains("concrete value");

        JsonNode unknown = wildcard.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknown).put("InitState", "on;eco");
        BadRequestException unknownException = assertThrows(BadRequestException.class,
                () -> validator.validateRawManifest("Ambiguous Mode Device", unknown));
        org.assertj.core.api.Assertions.assertThat(unknownException.getMessage())
                .contains("InitState 'on;eco'")
                .contains("not defined in WorkingStates");

        JsonNode caseAlias = wildcard.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) caseAlias).put("InitState", "On;normal");
        BadRequestException caseException = assertThrows(BadRequestException.class,
                () -> validator.validateRawManifest("Ambiguous Mode Device", caseAlias));
        org.assertj.core.api.Assertions.assertThat(caseException.getMessage())
                .contains("InitState 'On;normal'")
                .contains("not defined in WorkingStates");
    }

    @Test
    void canonicalSerialization_omitsNestedNullsAndRemainsSchemaValid() throws Exception {
        DeviceManifest manifest = DeviceManifest.builder()
                .name("Temperature Sensor")
                .modes(List.of("Detection"))
                .initState("ready")
                .workingStates(List.of(DeviceManifest.WorkingState.builder()
                        .name("ready")
                        .trust("trusted")
                        .privacy("public")
                        .build()))
                .internalVariables(List.of(DeviceManifest.InternalVariable.builder()
                        .name("temperature")
                        .isInside(false)
                        .reads(true)
                        .falsifiableWhenCompromised(true)
                        .trust("trusted")
                        .privacy("private")
                        .lowerBound(0)
                        .upperBound(100)
                        .naturalChangeRate("[-1, 1]")
                        .build()))
                .build();

        String canonicalJson = validator.toCanonicalJson(manifest);
        JsonNode canonicalNode = objectMapper.readTree(canonicalJson);

        org.assertj.core.api.Assertions.assertThat(canonicalJson).doesNotContain(":null");
        assertDoesNotThrow(() -> validator.validateRawManifest("Temperature Sensor", canonicalNode));
    }

    @Test
    void dtoManifestValidation_treatsNullOptionalNestedFieldsAsOmitted() {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setName("Lamp");
        manifest.setModes(List.of("LampState"));
        manifest.setInitState("off");

        DeviceManifest.WorkingState off = new DeviceManifest.WorkingState();
        off.setName("off");
        off.setTrust("trusted");
        off.setPrivacy("public");
        DeviceManifest.WorkingState on = new DeviceManifest.WorkingState();
        on.setName("on");
        on.setTrust("trusted");
        on.setPrivacy("public");
        manifest.setWorkingStates(List.of(off, on));

        assertDoesNotThrow(
                () -> validator.validateManifest("Lamp", manifest),
                "DTO validation should be equivalent to validating raw JSON with omitted optional fields");
    }

    @Test
    void apiTrigger_isRejectedByCanonicalSchema() throws Exception {
        JsonNode manifest = readLampManifest("""
                "APIs": [
                  {
                    "Name": "turn_on",
                    "EndState": "on",
                    "Trigger": {
                      "Attribute": "LampState",
                      "Relation": "=",
                      "Value": "off"
                    }
                  }
                ]
                """);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> validator.validateRawManifest("Lamp", manifest));

        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                .contains("backend/device-template-schema.json")
                .contains("Trigger");
    }

    @Test
    void apiAssignments_areRejectedInsteadOfSilentlyIgnored() throws Exception {
        JsonNode manifest = readLampManifest("""
                "InternalVariables": [{
                  "Name": "level",
                  "IsInside": true,
                  "FalsifiableWhenCompromised": false,
                  "Trust": "trusted",
                  "Privacy": "public",
                  "LowerBound": 0,
                  "UpperBound": 100
                }],
                "APIs": [{
                  "Name": "turn_on",
                  "EndState": "on",
                  "Assignments": [{"Attribute": "level", "Value": "100"}]
                }]
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Lamp", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("unsupported Assignments field")
                .contains("triggered Transition");
    }

    @Test
    void apiOnStatelessTemplate_isRejectedAsUnrepresentable() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Stateless Service",
                  "APIs": [{"Name": "notify", "EndState": "sent"}]
                }
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Stateless Service", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("APIs require at least one Mode")
                .contains("modeled as a state change");
    }

    @Test
    void apiEndState_mustChangeAtLeastOneMode() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Dual Mode Device",
                  "Modes": ["Power", "Profile"],
                  "InitState": "off;idle",
                  "WorkingStates": [{"Name": "off;idle"}],
                  "APIs": [{"Name": "do_nothing", "EndState": ";"}]
                }
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Dual Mode Device", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("API 'do_nothing'")
                .contains("changes no mode");
    }

    @Test
    void signalApis_withSameStateRouteAreRejectedAsIndistinguishable() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Lamp",
                  "Modes": ["Power"],
                  "InitState": "off",
                  "WorkingStates": [{"Name": "off"}, {"Name": "on"}],
                  "APIs": [
                    {"Name": "turn_on", "StartState": "off", "EndState": "on", "Signal": true},
                    {"Name": "activate", "StartState": "off", "EndState": "on", "Signal": true}
                  ]
                }
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Lamp", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("observable API 'turn_on' overlaps API 'activate'")
                .contains("cannot be distinguished as automation events");
    }

    @Test
    void apiSignalMustBeExplicit() throws Exception {
        JsonNode manifest = readLampManifest("""
                "APIs": [{"Name": "turn_on", "StartState": "off", "EndState": "on"}]
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Lamp", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains(DeviceTemplateSchemaValidator.CANONICAL_SCHEMA_PATH)
                .contains("Signal");
    }

    @Test
    void apiStartStateMustBeExplicit() throws Exception {
        JsonNode manifest = readLampManifest("""
                "APIs": [{"Name": "turn_on", "EndState": "on", "Signal": true}]
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Lamp", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains(DeviceTemplateSchemaValidator.CANONICAL_SCHEMA_PATH)
                .contains("StartState");
    }

    @Test
    void observableApiRouteCannotOverlapCommandOnlyApi() throws Exception {
        JsonNode manifest = readLampManifest("""
                "APIs": [
                  {"Name": "turn_on", "StartState": "off", "EndState": "on", "Signal": true},
                  {"Name": "restore", "StartState": "", "EndState": "on", "Signal": false}
                ]
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Lamp", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("observable API 'turn_on' overlaps API 'restore'")
                .contains("cannot be distinguished as automation events");
    }

    @Test
    void observableApiRouteCannotOverlapAutonomousTransition() throws Exception {
        JsonNode manifest = readLampManifest("""
                "APIs": [
                  {"Name": "turn_on", "StartState": "off", "EndState": "on", "Signal": true}
                ],
                "Transitions": [{
                  "Name": "automatic_on",
                  "StartState": "off",
                  "EndState": "on",
                  "Trigger": {"Attribute": "Power", "Relation": "=", "Value": "off"},
                  "Assignments": []
                }]
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Lamp", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("observable API 'turn_on' overlaps autonomous Transition 'automatic_on'")
                .contains("would expose that transition as the API event");
    }

    @Test
    void apiWithIdenticalConcreteStartAndEnd_isRejected() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Lamp",
                  "Modes": ["Power"],
                  "InitState": "on",
                  "WorkingStates": [{"Name": "on"}],
                  "APIs": [{"Name": "keep_on", "StartState": "on", "EndState": "on"}]
                }
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Lamp", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("identical concrete StartState and EndState")
                .contains("cannot change the formal model");
    }

    @Test
    void transitionSignal_isRejectedBecauseItHasNoUserReferenceSemantics() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Motion Sensor",
                  "InternalVariables": [{
                    "Name": "motion",
                    "IsInside": true,
                    "FalsifiableWhenCompromised": true,
                    "Trust": "untrusted",
                    "Privacy": "public",
                    "Values": ["clear", "detected"]
                  }],
                  "Transitions": [{
                    "Name": "motion detected",
                    "Signal": true,
                    "Trigger": {"Attribute": "motion", "Relation": "=", "Value": "detected"},
                    "Assignments": [{"Attribute": "motion", "Value": "clear"}]
                  }]
                }
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Motion Sensor", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("backend/device-template-schema.json")
                .contains("Signal");
    }

    @Test
    void transitionAssignment_requiresTriggerAndDeclaredTarget() throws Exception {
        JsonNode missingTrigger = readCounterManifest("""
                "Name": "reset",
                "Assignments": [{"Attribute": "counterValue", "Value": "0"}]
                """);
        BadRequestException missingTriggerException = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Counter", missingTrigger));
        org.assertj.core.api.Assertions.assertThat(missingTriggerException.getMessage())
                .contains("assigns variables but has no Trigger")
                .contains("never execute");

        JsonNode unknownTarget = readCounterManifest("""
                "Name": "reset",
                "Trigger": {"Attribute": "counterValue", "Relation": "=", "Value": "10"},
                "Assignments": [{"Attribute": "missing", "Value": "0"}]
                """);
        BadRequestException unknownTargetException = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Counter", unknownTarget));
        org.assertj.core.api.Assertions.assertThat(unknownTargetException.getMessage())
                .contains("assigns unknown variable 'missing'")
                // The message must name the array that still exists. It used to tell the author to add an
                // EnvironmentDomains entry, which the schema now rejects -- a dead-end instruction.
                .contains("declared in InternalVariables")
                .doesNotContain("EnvironmentDomains");
    }

    @Test
    void transitionAssignment_valueMustFitDeclaredDomain() throws Exception {
        JsonNode manifest = readCounterManifest("""
                "Name": "overflow",
                "Trigger": {"Attribute": "counterValue", "Relation": "=", "Value": "10"},
                "Assignments": [{"Attribute": "counterValue", "Value": "11"}]
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Counter", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("assigns 11 to 'counterValue'")
                .contains("outside its range 0..10");
    }

    @Test
    void triggeredTransitionAssignment_isRepresentable() throws Exception {
        JsonNode manifest = readCounterManifest("""
                "Name": "reset",
                "Trigger": {"Attribute": "counterValue", "Relation": "=", "Value": "10"},
                "Assignments": [{"Attribute": "counterValue", "Value": "0"}]
                """);

        assertDoesNotThrow(() -> validator.validateRawManifest("Counter", manifest));
    }

    @Test
    void transitionWithMultipleEffects_isRejectedInsteadOfPartiallyApplying() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Controller",
                  "Modes": ["Power"],
                  "InitState": "off",
                  "WorkingStates": [
                    {"Name": "off", "Trust": "trusted", "Privacy": "public"},
                    {"Name": "on", "Trust": "trusted", "Privacy": "public"}
                  ],
                  "InternalVariables": [{
                    "Name": "level",
                    "IsInside": true,
                    "FalsifiableWhenCompromised": false,
                    "Trust": "trusted",
                    "Privacy": "public",
                    "LowerBound": 0,
                    "UpperBound": 10
                  }],
                  "Transitions": [{
                    "Name": "activate",
                    "StartState": "off",
                    "EndState": "on",
                    "Trigger": {"Attribute": "level", "Relation": "=", "Value": "10"},
                    "Assignments": [{"Attribute": "level", "Value": "0"}]
                  }]
                }
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Controller", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("Transition 'activate'")
                .contains("cannot preserve those effects as one atomic action")
                .contains("single-effect transitions");
    }

    @Test
    void multiModeTransitionChangingTwoModes_isRejectedAsNonAtomic() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Dual Controller",
                  "Modes": ["Power", "Profile"],
                  "InitState": "off;eco",
                  "WorkingStates": [
                    {"Name": "off;eco", "Trust": "trusted", "Privacy": "public"},
                    {"Name": "on;boost", "Trust": "trusted", "Privacy": "public"}
                  ],
                  "Transitions": [{
                    "Name": "activate boost",
                    "StartState": "off;eco",
                    "EndState": "on;boost",
                    "Trigger": {"Attribute": "Power", "Relation": "=", "Value": "off"}
                  }]
                }
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Dual Controller", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("Transition 'activate boost'")
                .contains("2 state/variable effects")
                .contains("atomic action");
    }

    @Test
    void statelessTransitionCannotPretendToChangeState() throws Exception {
        JsonNode manifest = readCounterManifest("""
                "Name": "phantom state",
                "EndState": "active",
                "Trigger": {"Attribute": "counterValue", "Relation": "=", "Value": "10"}
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Counter", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("has no Modes")
                .contains("Stateless transitions")
                .contains("variable Assignment");
    }

    @Test
    void transitionTriggerMustUseAValueAndRelationFromItsDomain() throws Exception {
        JsonNode invalidValue = objectMapper.readTree("""
                {
                  "Name": "Switch",
                  "Modes": ["Power"],
                  "InitState": "off",
                  "WorkingStates": [
                    {"Name": "off", "Trust": "trusted", "Privacy": "public"},
                    {"Name": "on", "Trust": "trusted", "Privacy": "public"}
                  ],
                  "Transitions": [{
                    "Name": "invalid trigger",
                    "EndState": "on",
                    "Trigger": {"Attribute": "Power", "Relation": "=", "Value": "standby"}
                  }]
                }
                """);
        BadRequestException invalidValueException = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Switch", invalidValue));
        org.assertj.core.api.Assertions.assertThat(invalidValueException.getMessage())
                .contains("unknown value 'standby'")
                .contains("Allowed values");

        JsonNode validAliases = invalidValue.deepCopy();
        var trigger = (ObjectNode) validAliases.at("/Transitions/0/Trigger");
        trigger.put("Value", "off");
        for (String alias : List.of("EQ", "==", "NEQ")) {
            trigger.put("Relation", alias);
            assertDoesNotThrow(() -> validator.validateRawManifest("Switch", validAliases), alias);
        }

        JsonNode invalidRelation = objectMapper.readTree("""
                {
                  "Name": "Switch",
                  "Modes": ["Power"],
                  "InitState": "off",
                  "WorkingStates": [
                    {"Name": "off", "Trust": "trusted", "Privacy": "public"},
                    {"Name": "on", "Trust": "trusted", "Privacy": "public"}
                  ],
                  "Transitions": [{
                    "Name": "invalid relation",
                    "EndState": "on",
                    "Trigger": {"Attribute": "Power", "Relation": ">", "Value": "off"}
                  }]
                }
                """);
        BadRequestException invalidRelationException = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Switch", invalidRelation));
        org.assertj.core.api.Assertions.assertThat(invalidRelationException.getMessage())
                .contains("ordering relation")
                .contains("Use = or !=");
    }

    @Test
    void numericTransitionLiteralsMustUseAsciiDigits() throws Exception {
        var valid = (ObjectNode) objectMapper.readTree("""
                {"Name":"Counter", "InternalVariables":[{
                    "Name":"amount", "IsInside":true, "FalsifiableWhenCompromised":false,
                    "Trust":"trusted", "Privacy":"public", "LowerBound":-2, "UpperBound":2}],
                 "Transitions":[{"Name":"reset", "Trigger":{"Attribute":"amount","Relation":"=","Value":"+1"},
                    "Assignments":[{"Attribute":"amount","Value":"-1"}]}]}
                """);
        var nonAsciiTrigger = valid.deepCopy();
        ((ObjectNode) nonAsciiTrigger.at("/Transitions/0/Trigger")).put("Value", "+\u0661");
        var nonAsciiAssignment = valid.deepCopy();
        ((ObjectNode) nonAsciiAssignment.at("/Transitions/0/Assignments/0")).put("Value", "-\u0661");

        assertAll(
                () -> assertDoesNotThrow(() -> validator.validateRawManifest("Counter", valid)),
                () -> assertTrue(assertThrows(BadRequestException.class,
                        () -> validator.validateRawManifest("Counter", nonAsciiTrigger))
                        .getMessage().contains("numeric trigger 'amount'")),
                () -> assertTrue(assertThrows(BadRequestException.class,
                        () -> validator.validateRawManifest("Counter", nonAsciiAssignment))
                        .getMessage().contains("assigns non-integer value")));
    }

    @Test
    void rejectsLowerBoundThatNuSmvCannotParse() throws Exception {
        var manifest = (ObjectNode) objectMapper.readTree("""
                {"Name":"Counter", "InternalVariables":[{
                    "Name":"amount", "IsInside":true, "FalsifiableWhenCompromised":false,
                    "Trust":"trusted", "Privacy":"public", "LowerBound":-2147483648, "UpperBound":-2147483646}]}
                """);
        var error = assertThrows(BadRequestException.class, () -> validator.validateRawManifest("Counter", manifest));
        assertTrue(error.getMessage().contains("-2147483647"));
    }

    @Test
    void workingStateDynamicsMustTargetWritableVariableWithDomainAppropriateEffect() throws Exception {
        JsonNode unknownTarget = objectMapper.readTree("""
                {
                  "Name": "Heater",
                  "Modes": ["Power"],
                  "InitState": "on",
                  "WorkingStates": [{
                    "Name": "on",
                    "Trust": "trusted",
                    "Privacy": "public",
                    "Dynamics": [{"VariableName": "temperature", "ChangeRate": "1"}]
                  }]
                }
                """);
        BadRequestException unknownTargetException = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Heater", unknownTarget));
        org.assertj.core.api.Assertions.assertThat(unknownTargetException.getMessage())
                .contains("unknown or non-writable variable 'temperature'")
                .contains("ImpactedVariables");

        JsonNode wrongEffect = objectMapper.readTree("""
                {
                  "Name": "Door",
                  "Modes": ["Position"],
                  "InitState": "closed",
                  "WorkingStates": [{
                    "Name": "closed",
                    "Trust": "trusted",
                    "Privacy": "public",
                    "Dynamics": [{"VariableName": "contact", "ChangeRate": "1"}]
                  }],
                  "InternalVariables": [{
                    "Name": "contact",
                    "IsInside": true,
                    "FalsifiableWhenCompromised": false,
                    "Trust": "trusted",
                    "Privacy": "public",
                    "Values": ["closed", "open"]
                  }]
                }
                """);
        BadRequestException wrongEffectException = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Door", wrongEffect));
        org.assertj.core.api.Assertions.assertThat(wrongEffectException.getMessage())
                .contains("must use Value for enum/boolean Dynamics target 'contact'")
                .contains("no discrete-domain meaning");
    }

    @Test
    void workingStateExplicitBooleanEnumDynamicsIsAcceptedAndOutOfDomainValueIsRejected() throws Exception {
        JsonNode valid = objectMapper.readTree("""
                {
                  "Name": "Presence Latch",
                  "Modes": ["Power"],
                  "InitState": "on",
                  "WorkingStates": [{
                    "Name": "on",
                    "Trust": "trusted",
                    "Privacy": "public",
                    "Dynamics": [{"VariableName": "occupied", "Value": "TRUE"}]
                  }],
                  "InternalVariables": [{
                    "Name": "occupied",
                    "IsInside": true,
                    "FalsifiableWhenCompromised": false,
                    "Trust": "trusted",
                    "Privacy": "public",
                    "Values": ["TRUE", "FALSE"]
                  }]
                }
                """);
        assertDoesNotThrow(() -> validator.validateRawManifest("Presence Latch", valid));

        JsonNode invalid = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("WorkingStates").get(0)
                .path("Dynamics").get(0)).put("Value", "maybe");
        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Presence Latch", invalid));
        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("Dynamics target 'occupied'")
                .contains("outside its enum domain")
                .contains("TRUE")
                .contains("FALSE");
    }

    @Test
    void variableDomainsRejectDescendingRangesAndNormalizedEnumDuplicates() throws Exception {
        JsonNode descending = objectMapper.readTree("""
                {
                  "Name": "Meter",
                  "InternalVariables": [{
                    "Name": "level",
                    "IsInside": true,
                    "FalsifiableWhenCompromised": false,
                    "Trust": "trusted",
                    "Privacy": "public",
                    "LowerBound": 10,
                    "UpperBound": 0
                  }]
                }
                """);
        BadRequestException descendingException = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Meter", descending));
        org.assertj.core.api.Assertions.assertThat(descendingException.getMessage())
                .contains("LowerBound 10 greater than UpperBound 0");

        JsonNode duplicateEnum = objectMapper.readTree("""
                {
                  "Name": "Status",
                  "InternalVariables": [{
                    "Name": "state",
                    "IsInside": true,
                    "FalsifiableWhenCompromised": false,
                    "Trust": "trusted",
                    "Privacy": "public",
                    "Values": ["not ready", "notready"]
                  }]
                }
                """);
        BadRequestException duplicateException = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Status", duplicateEnum));
        org.assertj.core.api.Assertions.assertThat(duplicateException.getMessage())
                .contains("duplicate enum value 'notready'")
                .contains("spaces are removed");
    }

    @Test
    void naturalChangeRateRequiresNumericDomainAndSupportedIntegerRange() throws Exception {
        JsonNode enumRate = objectMapper.readTree("""
                {
                  "Name": "Status",
                  "InternalVariables": [{
                    "Name": "status",
                    "IsInside": true,
                    "FalsifiableWhenCompromised": false,
                    "Trust": "trusted",
                    "Privacy": "public",
                    "Values": ["ready", "busy"],
                    "NaturalChangeRate": "1"
                  }]
                }
                """);
        BadRequestException enumException = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Status", enumRate));
        org.assertj.core.api.Assertions.assertThat(enumException.getMessage())
                .contains("only numeric ranges can change by a rate");

        JsonNode overflow = objectMapper.readTree("""
                {
                  "Name": "Meter",
                  "InternalVariables": [{
                    "Name": "level",
                    "IsInside": true,
                    "FalsifiableWhenCompromised": false,
                    "Trust": "trusted",
                    "Privacy": "public",
                    "LowerBound": 0,
                    "UpperBound": 100,
                    "NaturalChangeRate": "999999999999"
                  }]
                }
                """);
        BadRequestException overflowException = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Meter", overflow));
        org.assertj.core.api.Assertions.assertThat(overflowException.getMessage())
                .contains("outside the supported integer format")
                .contains("999999999999");
    }

    @Test
    void sharedNumericEnvironmentRequiresExplicitNaturalChangeRate() throws Exception {
        JsonNode shared = objectMapper.readTree("""
                {
                  "Name": "Temperature Sensor",
                  "InternalVariables": [{
                    "Name": "temperature",
                    "IsInside": false,
                    "FalsifiableWhenCompromised": true,
                    "Trust": "untrusted",
                    "Privacy": "public",
                    "LowerBound": 0,
                    "UpperBound": 100
                  }]
                }
                """);
        BadRequestException sharedException = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Temperature Sensor", shared));
        org.assertj.core.api.Assertions.assertThat(sharedException.getMessage())
                .contains("NaturalChangeRate");

        JsonNode local = objectMapper.readTree("""
                {
                  "Name": "Local Counter",
                  "InternalVariables": [{
                    "Name": "counter",
                    "IsInside": true,
                    "FalsifiableWhenCompromised": false,
                    "Trust": "trusted",
                    "Privacy": "public",
                    "LowerBound": 0,
                    "UpperBound": 100
                  }]
                }
                """);
        assertDoesNotThrow(() -> validator.validateRawManifest("Local Counter", local));
    }

    @Test
    void sharedEnvironmentVariable_requiresExplicitTrustAndPrivacy() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Temperature Sensor",
                  "InternalVariables": [{
                    "Name": "temperature",
                    "IsInside": false,
                    "FalsifiableWhenCompromised": true,
                    "LowerBound": 0,
                    "UpperBound": 100,
                    "Trust": "untrusted"
                  }]
                }
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Temperature Sensor", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage()).contains("Privacy");
    }

    @Test
    void impactOnlySharedDeclaration_requiresExplicitTrustAndPrivacy() throws Exception {
        // Affect-only is now one declaration in the single array, with read capability withheld
        // explicitly rather than by living in a second array.
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Light",
                  "InternalVariables": [{
                    "Name": "illuminance",
                    "IsInside": false,
                    "Reads": false,
                    "FalsifiableWhenCompromised": false,
                    "LowerBound": 0,
                    "UpperBound": 100,
                    "Privacy": "public"
                  }],
                  "ImpactedVariables": ["illuminance"]
                }
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Light", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage()).contains("Trust");
    }

    @Test
    void everyModeledSourceAndContent_requiresExplicitSecurityLabels() throws Exception {
        JsonNode missingStateLabels = objectMapper.readTree("""
                {
                  "Name": "State Sensor",
                  "Modes": ["Detection"],
                  "InitState": "clear",
                  "WorkingStates": [{"Name": "clear"}]
                }
                """);
        JsonNode missingLocalVariableLabels = objectMapper.readTree("""
                {
                  "Name": "Local Sensor",
                  "InternalVariables": [{
                    "Name": "reading",
                    "IsInside": true,
                    "FalsifiableWhenCompromised": true,
                    "LowerBound": 0,
                    "UpperBound": 100
                  }]
                }
                """);
        JsonNode missingContentPrivacy = objectMapper.readTree("""
                {
                  "Name": "Camera",
                  "Contents": [{"Name": "photo"}]
                }
                """);

        BadRequestException stateException = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("State Sensor", missingStateLabels));
        BadRequestException variableException = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Local Sensor", missingLocalVariableLabels));
        BadRequestException contentException = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Camera", missingContentPrivacy));

        org.assertj.core.api.Assertions.assertThat(stateException.getMessage())
                .contains("WorkingStates[0]").contains("Trust").contains("Privacy");
        org.assertj.core.api.Assertions.assertThat(variableException.getMessage())
                .contains("InternalVariables[0]").contains("Trust").contains("Privacy");
        org.assertj.core.api.Assertions.assertThat(contentException.getMessage())
                .contains("Contents[0]").contains("Privacy");
    }

    @Test
    void rawWorkingStateInvariant_isRejectedInsteadOfPretendingToConstrainTheModel() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Lamp",
                  "Modes": ["Power"],
                  "InitState": "off",
                  "WorkingStates": [{
                    "Name": "off",
                    "Trust": "trusted",
                    "Privacy": "public",
                    "Invariant": "level < 50"
                  }]
                }
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Lamp", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("backend/device-template-schema.json")
                .contains("Invariant");
    }

    @Test
    void multiModeWorkingState_requiresOneConcreteValuePerMode() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Washer",
                  "Modes": ["Program", "MachineState"],
                  "InitState": "regular;idle",
                  "WorkingStates": [
                    {"Name": "regular;idle", "Trust": "trusted", "Privacy": "public"},
                    {"Name": "running", "Trust": "trusted", "Privacy": "public"}
                  ]
                }
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Washer", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("WorkingState 'running'")
                .contains("each mode");
    }

    @Test
    void reusedModeState_rejectsConflictingTrustOrPrivacyLabels() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "Home Profile",
                  "Modes": ["Occupancy", "MachineState"],
                  "InitState": "home;idle",
                  "WorkingStates": [
                    {"Name": "home;idle", "Trust": "trusted", "Privacy": "public"},
                    {"Name": "away;idle", "Trust": "untrusted", "Privacy": "public"}
                  ]
                }
                """);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                validator.validateRawManifest("Home Profile", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("home;idle")
                .contains("away;idle")
                .contains("MachineState='idle'")
                .contains("cannot be represented losslessly");
    }

    @Test
    void apiAssignmentsFieldIsRejectedEvenWhenEmpty() throws Exception {
        JsonNode manifest = readLampManifest("""
                "APIs": [{
                  "Name": "turn_on",
                  "StartState": "off",
                  "EndState": "on",
                  "Signal": true,
                  "Assignments": []
                }]
                """);

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> validator.validateRawManifest("Lamp", manifest));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("unsupported Assignments field")
                .contains("triggered Transition");
    }

    private JsonNode readLampManifest(String additionalFields) throws JsonProcessingException {
        return objectMapper.readTree("""
                {
                  "Name": "Lamp",
                  "Modes": ["Power"],
                  "InitState": "off",
                  "WorkingStates": [
                    {"Name": "off", "Trust": "trusted", "Privacy": "public"},
                    {"Name": "on", "Trust": "trusted", "Privacy": "public"}
                  ],
                  %s
                }
                """.formatted(additionalFields));
    }

    private JsonNode readCounterManifest(String transitionFields) throws JsonProcessingException {
        return objectMapper.readTree("""
                {
                  "Name": "Counter",
                  "InternalVariables": [{
                    "Name": "counterValue",
                    "IsInside": true,
                    "FalsifiableWhenCompromised": false,
                    "Trust": "trusted",
                    "Privacy": "public",
                    "LowerBound": 0,
                    "UpperBound": 10
                  }],
                  "Transitions": [{
                    %s
                  }]
                }
                """.formatted(transitionFields));
    }

    private void assertVariableTrust(String templateName, String variableName, String expectedTrust)
            throws Exception {
        JsonNode manifest = objectMapper.readTree(
                Path.of("src/main/resources/deviceTemplate", templateName + ".json").toFile());
        for (JsonNode variable : manifest.path("InternalVariables")) {
            if (variableName.equals(variable.path("Name").asText())) {
                assertEquals(expectedTrust, variable.path("Trust").asText(),
                        () -> templateName + "." + variableName
                                + " should use MEDIC origin semantics");
                return;
            }
        }
        throw new AssertionError("Missing variable " + templateName + "." + variableName);
    }

    /**
     * The template endpoint accepts a raw {@code JsonNode} and builds the DTO with
     * {@code treeToValue}, so bean validation never runs on that path — the {@code @AssertTrue} guard
     * on the DTO is dead code there. This schema is the authoritative gate, and a live REST call
     * proved the gap: a manifest omitting {@code Reads} was accepted with 200 before this clause
     * existed, which let an AI-created template gain read capability from a missing field.
     */
    @Test
    void sharedDeclarationWithoutReadsIsRejectedBySchema() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "MissingReads",
                  "InternalVariables": [{
                    "Name": "lux",
                    "IsInside": false,
                    "FalsifiableWhenCompromised": false,
                    "Trust": "trusted",
                    "Privacy": "public",
                    "Values": ["dim", "bright"]
                  }],
                  "ImpactedVariables": ["lux"]
                }
                """);

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> validator.validateRawManifest("MissingReads", manifest));
        assertTrue(error.getMessage().contains("Reads"), error.getMessage());
    }

    @Test
    void deviceLocalDeclarationWithReadsIsRejectedBySchema() throws Exception {
        // Reads is meaningless on a device-local variable: a device always reads its own state. Allowing
        // it would create two ways to say one thing and invite a reader to think it changes something.
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "LocalReads",
                  "InternalVariables": [{
                    "Name": "battery",
                    "IsInside": true,
                    "Reads": true,
                    "FalsifiableWhenCompromised": false,
                    "Trust": "trusted",
                    "Privacy": "public",
                    "LowerBound": 0,
                    "UpperBound": 100
                  }]
                }
                """);

        assertThrows(BadRequestException.class,
                () -> validator.validateRawManifest("LocalReads", manifest));
    }

    @Test
    void bothReadCapabilitiesAreAcceptedWhenStatedExplicitly() throws Exception {
        for (String reads : new String[]{"true", "false"}) {
            JsonNode manifest = objectMapper.readTree("""
                    {
                      "Name": "Explicit",
                      "InternalVariables": [{
                        "Name": "lux",
                        "IsInside": false,
                        "Reads": %s,
                        "FalsifiableWhenCompromised": false,
                        "Trust": "trusted",
                        "Privacy": "public",
                        "Values": ["dim", "bright"]
                      }],
                      "ImpactedVariables": ["lux"]
                    }
                    """.formatted(reads));

            validator.validateRawManifest("Explicit", manifest);
        }
    }

    /*
     * The schema is the authoritative gate for manifest collection sizes, and this test exists to record that.
     *
     * A dead-code audit reported the ten `RequestLimits.MAX_TEMPLATE_*` constants as "declared and never
     * referenced", concluding the manifest collections were unbounded on an authenticated write path. I acted on
     * it and added `@Size` to `DeviceTemplateDto` — which was wrong twice over: the endpoint takes a raw
     * `JsonNode` and calls `validateRawManifest` *before* converting to the DTO, so Bean Validation never sees a
     * manifest, and the schema already carried every one of those bounds as `maxItems`. The schema's own
     * `$comment` says so. Verified live afterwards: a 21-mode template is rejected 400 with
     * "$.Modes: at most 20 items, found 21".
     *
     * So an unreferenced constant is not automatically dead — this one documents the Java-side value of a limit
     * whose mechanism lives in another language. The check below makes the *mechanism* visible to anyone who
     * grep-audits those constants next time.
     */
    @Test
    void schemaRejectsAManifestExceedingAModeCap() throws Exception {
        StringBuilder modes = new StringBuilder();
        for (int index = 0; index <= cn.edu.nju.Iot_Verify.dto.RequestLimits.MAX_TEMPLATE_MODES; index++) {
            if (index > 0) modes.append(", ");
            modes.append('"').append('m').append(index).append('"');
        }
        JsonNode manifest = objectMapper.readTree("""
                {
                  "Name": "OverCap",
                  "Description": "probe",
                  "Modes": [%s],
                  "InitState": "m0",
                  "WorkingStates": []
                }
                """.formatted(modes));

        BadRequestException failure = assertThrows(BadRequestException.class,
                () -> validator.validateRawManifest("OverCap", manifest));
        // The message must name the field and the bound; a user who hits this has to know what to shorten.
        assertTrue(failure.getMessage().contains("Modes"),
                "the rejection should name the offending field: " + failure.getMessage());
    }

    @Test
    void schemaBoundsEveryCollectionThatRequestLimitsDeclares() throws Exception {
        // Guards the inverse of the audit's mistake: a `MAX_TEMPLATE_*` constant whose schema `maxItems` was
        // dropped would leave a documented limit with no mechanism at all, which is the defect the audit
        // *thought* it had found.
        JsonNode schema = objectMapper.readTree(Path.of("device-template-schema.json").toFile());
        for (String field : List.of("Modes", "WorkingStates", "InternalVariables", "ImpactedVariables",
                "Transitions", "APIs", "Contents")) {
            JsonNode node = schema.path("properties").path(field);
            assertTrue(node.has("maxItems"), field + " should carry a maxItems bound in the schema");
        }
        assertEquals(cn.edu.nju.Iot_Verify.dto.RequestLimits.MAX_TEMPLATE_MODES,
                schema.path("properties").path("Modes").path("maxItems").asInt(),
                "the schema bound and RequestLimits should agree on Modes");
    }

    @Test
    void noBundledDeviceStartsInAnAlertingState() throws Exception {
        // InitState is the state a device holds the moment it is dragged onto the canvas, and 27 of the 29
        // stateful bundled templates simply take WorkingStates[0]. That is harmless while entry 0 is a resting
        // state, but Alarm's list is alphabetical ("both", "off", "siren", "strobe"), so entry 0 was `both`:
        // a freshly placed alarm sat with siren *and* strobe already sounding. On a verification platform that
        // is worse than cosmetic — a safety property saying the alarm must not sound without cause is violated
        // at step 0 by the initial state alone, before any rule fires.
        //
        // The invariant is deliberately narrow. An earlier attempt asserted that InitState must equal the
        // state the bundled example scenes give the device, which looked stronger and was wrong: four of the
        // five overridden templates already agreed, and the fifth was Light, which those scenes set to "off"
        // because they depict night and away-from-home situations. A light starting on is an ordinary default,
        // not a defect, so that test would have reported scene intent as a bug. Alerting outputs are the case
        // where a non-resting default is indefensible regardless of scenario, so name exactly those.
        Set<String> alertingStates = Set.of("both", "siren", "strobe");

        Path templateDir = Path.of("src/main/resources/deviceTemplate");
        List<Path> templates;
        try (Stream<Path> stream = Files.list(templateDir)) {
            templates = stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        assertFalse(templates.isEmpty(), "default templates should exist");

        List<String> offenders = new ArrayList<>();
        int alarmLike = 0;
        for (Path template : templates) {
            JsonNode manifest = objectMapper.readTree(template.toFile());
            String initState = manifest.path("InitState").asText("");
            if (initState.isBlank()) continue;

            // Only devices that actually own an alerting state are in scope; every segment of a composite
            // state counts, so a multi-mode device cannot hide one behind a mode value.
            List<String> declared = new ArrayList<>();
            for (JsonNode state : manifest.path("WorkingStates")) {
                for (String segment : state.path("Name").asText("").split("[;,|]")) declared.add(segment.trim());
            }
            if (declared.stream().noneMatch(alertingStates::contains)) continue;
            alarmLike++;

            for (String segment : initState.split("[;,|]")) {
                if (alertingStates.contains(segment.trim())) {
                    offenders.add(template.getFileName() + ": InitState=\"" + initState
                            + "\" starts the device in alerting state \"" + segment.trim() + "\"");
                }
            }
        }

        // A scan that matches nothing asserts nothing: if no bundled template declares an alerting state,
        // this test cannot fail and must not be read as evidence.
        assertTrue(alarmLike > 0, "expected at least one bundled template with an alerting state");
        assertTrue(offenders.isEmpty(),
                "a bundled device must not be placed already alerting:\n" + String.join("\n", offenders));
    }

    /**
     * A device's starting state and its local variables must describe the same situation.
     *
     * <p>Six bundled templates disagreed, because the state came from `InitState` and the variable from
     * `Values[0]` — two independent sources, never compared. `Car` generated `init(CarLocation) := away`
     * beside `init(location) := garage`: a step-0 state its own transition relation calls impossible, which
     * makes an `AG` property refutable and an `EF` property provable on a configuration the device cannot
     * occupy. Nothing caught it because nothing compared the two.
     *
     * <p>This asserts the invariant on the manifests, so a new or edited template cannot reintroduce it;
     * `DeviceManifestModes.localInitialValue` is what makes the generated model satisfy it.
     */
    @Test
    void everyBundledTemplateStartsItsLocalVariablesConsistentlyWithItsInitialState() throws Exception {
        Path templateDir = Path.of("src/main/resources/deviceTemplate");
        List<Path> templates;
        try (Stream<Path> stream = Files.list(templateDir)) {
            templates = stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        assertFalse(templates.isEmpty(), "default templates should exist");

        List<String> offenders = new ArrayList<>();
        int checked = 0;
        int reconciled = 0;
        for (Path template : templates) {
            DeviceManifest manifest = objectMapper.readValue(template.toFile(), DeviceManifest.class);
            String initState = manifest.getInitState();
            if (initState == null || initState.isBlank() || manifest.getInternalVariables() == null) continue;

            for (DeviceManifest.InternalVariable variable : manifest.getInternalVariables()) {
                if (variable == null || !Boolean.TRUE.equals(variable.getIsInside())) continue;
                String declared = DeviceManifestModes.stateDeclaredValue(
                        manifest, initState, variable.getName());
                if (declared == null) continue;
                checked++;
                // Compare against the value the OLD rule would have produced, read straight from the
                // manifest. Asserting `localInitialValue` against `stateDeclaredValue` would be a tautology:
                // both call the same helper, so it would pass even if the helper were wrong.
                String independent = variable.getValues() != null && !variable.getValues().isEmpty()
                        ? variable.getValues().get(0)
                        : null;
                String resolved = DeviceManifestModes.localInitialValue(manifest, variable, initState);
                if (!declared.equals(resolved)) {
                    offenders.add(template.getFileName() + ": " + variable.getName()
                            + " starts at " + resolved + " while state \"" + initState + "\" declares " + declared);
                }
                if (independent != null && !independent.equals(resolved)) {
                    // Not a failure: this is the six templates whose enum order disagrees with their initial
                    // state, which is exactly what the derivation exists to reconcile. Recorded so the count
                    // below cannot silently drop to zero and make this test vacuous.
                    reconciled++;
                }
            }
        }

        // A scan that matches nothing asserts nothing.
        assertTrue(checked > 0, "expected bundled templates whose initial state constrains a local variable");
        // The invariant itself, reported first so a regression names the offending template, not a count.
        assertTrue(offenders.isEmpty(),
                "a device's starting state and its local variables must agree:\n" + String.join("\n", offenders));
        // Six templates declare an enum whose first literal is not their initial state's value, so the
        // derivation is doing real work. If this reaches zero the templates were reordered instead, and this
        // test would pass while proving nothing.
        assertTrue(reconciled > 0,
                "expected at least one template whose enum order disagrees with its initial state");
    }
}
