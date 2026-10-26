package cn.edu.nju.Iot_Verify.service.recommendation;

import cn.edu.nju.Iot_Verify.dto.recommendation.*;
import cn.edu.nju.Iot_Verify.exception.BadGatewayException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecommendationResultParserTest {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT, false);
    private final RecommendationResultParser parser = new RecommendationResultParser(objectMapper);

    @SuppressWarnings("unchecked")
    private Map<String, Object> rawMap(String json) throws Exception {
        return objectMapper.readValue(json, Map.class);
    }

    @Test
    void inconsistentSimilarityResult_throwsBadGateway() throws Exception {
        Map<String, Object> raw = rawMap("""
                {"isSimilar":false,"isDuplicate":true,"requiresReview":true,
                 "matchedRule":"Existing rule","similarity":1.0,"reasonCode":"AI_DUPLICATE",
                 "reason":"same semantics","message":"duplicate"}
                """);

        assertThrows(BadGatewayException.class,
                () -> parser.parseRuleSimilarityResult(raw));
    }

    @Test
    void completeRuleRecommendationResult_mapsToTypedDto() throws Exception {
        Map<String, Object> raw = rawMap("""
                {"message":"One rule found.","count":1,"requestedCount":5,
                 "validatedCount":1,"filteredCount":0,"filteredItems":[],
                 "adjustedCount":1,"adjustedItems":[{"type":"rule","index":1,
                 "reasonCode":"apiEventSyntaxNormalized","reason":"Equivalent event syntax normalized",
                 "label":"Alert on motion","appliedValues":{"sourceApi":"motion"}}],
                 "rawCandidateCount":1,"inspectedCount":1,"truncatedCount":0,
                 "recommendations":[{"name":"Alert on motion",
                 "conditions":[{"deviceId":"device_1",
                 "deviceLabel":"Hall sensor","deviceName":"Hall sensor","attribute":"motion",
                 "targetType":"api"}],"command":{"deviceId":"device_2",
                 "deviceLabel":"Alarm","deviceName":"Alarm","action":"turn_on"}}]}
                """);

        var response = parser.parseRecommendationResponse(raw, RuleRecommendationDto.class, true, "AI rule recommendation");

        assertEquals(1, response.getAdjustedCount());
        assertEquals("apiEventSyntaxNormalized", response.getAdjustedItems().get(0).getReasonCode());
    }

    @Test
    void incompleteRecommendationAccounting_throwsBadGateway() throws Exception {
        Map<String, Object> raw = rawMap("""
                {"message":"One rule found.","count":1,"requestedCount":5,
                 "validatedCount":1,"filteredCount":0,"filteredItems":[],
                 "adjustedCount":0,"adjustedItems":[],
                 "rawCandidateCount":2,"inspectedCount":1,"truncatedCount":0,
                 "recommendations":[{"name":"Alert on motion",
                 "conditions":[],"command":{}}]}
                """);

        assertThrows(BadGatewayException.class,
                () -> parser.parseRecommendationResponse(raw, RuleRecommendationDto.class, true, "AI rule recommendation"));
    }

    @Test
    void keptRuleRecommendationWithoutPersistedName_throwsBadGateway() throws Exception {
        Map<String, Object> raw = rawMap("""
                {"message":"One rule found.","count":1,"requestedCount":5,
                 "validatedCount":1,"filteredCount":0,"filteredItems":[],
                 "adjustedCount":0,"adjustedItems":[],
                 "rawCandidateCount":1,"inspectedCount":1,"truncatedCount":0,
                 "recommendations":[{"conditions":[{"deviceId":"device_1",
                 "deviceName":"Hall sensor","attribute":"motion","targetType":"api"}],
                 "command":{"deviceId":"device_2","deviceName":"Alarm","action":"turn_on"}}]}
                """);

        assertThrows(BadGatewayException.class,
                () -> parser.parseRecommendationResponse(raw, RuleRecommendationDto.class, true, "AI rule recommendation"));
    }

    @Test
    void unknownKeptRecommendationField_throwsBadGateway() throws Exception {
        Map<String, Object> raw = rawMap("""
                {"message":"One rule found.","count":1,"requestedCount":5,
                 "validatedCount":1,"filteredCount":0,"filteredItems":[],
                 "adjustedCount":0,"adjustedItems":[],
                 "rawCandidateCount":1,"inspectedCount":1,"truncatedCount":0,
                 "recommendations":[{"name":"Alert on motion",
                 "conditions":[],"command":{},"unmodeledEffect":"open door"}]}
                """);

        assertThrows(BadGatewayException.class,
                () -> parser.parseRecommendationResponse(raw, RuleRecommendationDto.class, true, "AI rule recommendation"));
    }

    @Test
    void deviceRecommendationRequestAndAdjustments_mapToTypedDto() throws Exception {
        Map<String, Object> raw = rawMap("""
                {"message":"One device found.","count":1,"requestedCount":5,
                 "validatedCount":1,"filteredCount":0,"filteredItems":[],
                 "adjustedCount":1,"adjustedItems":[{"type":"device","index":1,
                 "reasonCode":"deviceDefaultsApplied","reason":"Defaults applied",
                 "label":"Hall sensor","appliedValues":{"initialState":"idle"}}],
                 "rawCandidateCount":1,"inspectedCount":1,"truncatedCount":0,
                 "recommendations":[{"templateName":"Motion Detector",
                 "suggestedLabel":"Hall sensor","intendedUse":"trigger",
                 "suggestedPlacement":"hall",
                 "description":"Detect motion","reason":"Supports automation",
                 "initialState":"idle","currentStateTrust":"trusted",
                 "currentStatePrivacy":"public"}]}
                """);

        assertDoesNotThrow(() -> parser.parseRecommendationResponse(raw, DeviceRecommendationDto.class, true, "AI device recommendation"));
    }

    @Test
    void specificationRecommendation_mapsToTypedDto() throws Exception {
        Map<String, Object> raw = rawMap("""
                {"message":"One specification found.","count":1,"requestedCount":5,
                 "validatedCount":1,"filteredCount":0,"filteredItems":[],
                 "rawCandidateCount":1,"inspectedCount":1,"truncatedCount":0,
                 "recommendations":[{"rationale":"Motion must trigger alarm",
                 "name": "Safety requirement", "templateId":"5","aConditions":[],
                 "ifConditions":[{"deviceId":"device_1","deviceLabel":"Hall sensor",
                 "targetType":"api","key":"motion","relation":"=","value":"TRUE"}],
                 "thenConditions":[{"deviceId":"device_2",
                 "deviceLabel":"Alarm","targetType":"state","key":"state",
                 "relation":"=","value":"on"}]}]}
                """);

        assertDoesNotThrow(() -> parser.parseRecommendationResponse(raw, SpecificationRecommendationDto.class, false, "AI specification recommendation"));
    }

    @Test
    void keptSpecificationRecommendationWithoutRationale_throwsBadGateway() throws Exception {
        Map<String, Object> raw = rawMap("""
                {"message":"One specification found.","count":1,"requestedCount":5,
                 "validatedCount":1,"filteredCount":0,"filteredItems":[],
                 "rawCandidateCount":1,"inspectedCount":1,"truncatedCount":0,
                 "recommendations":[{"name": "Safety requirement", "templateId":"3","aConditions":[],
                 "ifConditions":[],"thenConditions":[]}]}
                """);

        assertThrows(BadGatewayException.class,
                () -> parser.parseRecommendationResponse(raw, SpecificationRecommendationDto.class, false, "AI specification recommendation"));
    }

    @Test
    void scenarioFinalCountMayIncludeSynthesizedEnvironmentItem() throws Exception {
        Map<String, Object> raw = rawMap("""
                {"message":"Scenario generated.","count":3,"requestedCount":3,
                 "validatedCount":2,"filteredCount":0,"filteredItems":[],
                 "adjustedCount":1,"adjustedItems":[{"type":"environment",
                 "reasonCode":"missingEnvironmentAdded","reason":"Required value added",
                 "label":"temperature","appliedValues":{"value":"20"}}],
                 "rawCandidateCount":2,"inspectedCount":2,"truncatedCount":0,
                 "scenarioName":"Home","rationale":"Exercise a shared reading",
                 "objectiveTargets":{"minDevices":1,"minRules":1,"minSpecs":1},
                 "objectiveStatus":"PARTIAL","objectiveIssues":[
                   {"code":"NO_AUTOMATION_RULES","message":"No automation rule."}],
                 "verificationReady":true,"readinessIssues":[],
                 "semanticWarnings":[{"code":"NO_AUTOMATION_RULES",
                 "message":"The final draft contains no retained automation rules."}],
                 "scene":{"schema":"iot-verify.board-scene","version":6,
                 "templates":[{"name":"Sensor","manifest":{"Name":"Sensor"}}],
                 "devices":[{"id":"device_1","templateName":"Sensor","label":"Hall sensor",
                 "position":{"x":0,"y":0},"width":176,"height":128}],
                 "environmentVariables":[{"name":"temperature","value":"20",
                 "trust":"trusted","privacy":"public"}],"rules":[],
                 "specs":[{"name": "Safety requirement", "templateId":"3","aConditions":[{"deviceId":"device_1",
                 "targetType":"variable","key":"temperature","relation":">","value":"30"}],
                 "ifConditions":[],"thenConditions":[]}]}}
                """);

        ScenarioRecommendationResponseDto response = parser.parseScenarioRecommendationResponse(raw, validScenarioRequest());
        JsonNode scene = objectMapper.valueToTree(response.getScene());

        assertEquals(List.of("manifest", "name"),
                iterableFieldNames(scene.path("templates").get(0)));
        JsonNode device = scene.path("devices").get(0);
        assertFalse(device.has("state"));
        assertFalse(device.has("currentStateTrust"));
        assertFalse(device.has("currentStatePrivacy"));
        assertEquals("device_1", device.path("id").asText());
        JsonNode condition = scene.path("specs").get(0).path("aConditions").get(0);
        assertEquals(List.of("deviceId", "key", "relation", "targetType", "value"),
                iterableFieldNames(condition));
        assertEquals("PARTIAL", response.getObjectiveStatus());
        assertEquals("NO_AUTOMATION_RULES", response.getObjectiveIssues().get(0).getCode());
        assertTrue(response.getVerificationReady());
        assertTrue(response.getReadinessIssues().isEmpty());
        assertEquals("NO_AUTOMATION_RULES", response.getSemanticWarnings().get(0).getCode());
    }

    @Test
    void scenarioReadinessMustMatchScene() throws Exception {
        Map<String, Object> raw = rawMap("""
                {"message":"Scenario generated.","count":1,"requestedCount":3,
                 "validatedCount":1,"filteredCount":0,"filteredItems":[],
                 "adjustedCount":0,"adjustedItems":[],
                 "rawCandidateCount":1,"inspectedCount":1,"truncatedCount":0,
                 "scenarioName":"Home","rationale":"Missing a specification",
                 "objectiveTargets":{"minDevices":1,"minRules":1,"minSpecs":1},
                 "objectiveStatus":"PARTIAL","objectiveIssues":[
                   {"code":"NO_AUTOMATION_RULES","message":"No automation rule."},
                   {"code":"NO_SPECIFICATIONS","message":"No formal specification."}],
                 "verificationReady":true,"readinessIssues":[],
                 "semanticWarnings":[
                   {"code":"NO_AUTOMATION_RULES","message":"No retained rules."},
                   {"code":"UNREFERENCED_DEVICES","message":"One device is unreferenced."}
                 ],
                 "scene":{"schema":"iot-verify.board-scene","version":6,
                 "templates":[],"devices":[{"id":"device_1","templateName":"Sensor",
                 "label":"Hall sensor","position":{"x":0,"y":0},"width":176,"height":128}],
                 "environmentVariables":[],"rules":[],"specs":[]}}
                """);

        assertThrows(BadGatewayException.class,
                () -> parser.parseScenarioRecommendationResponse(raw, validScenarioRequest()));
    }

    @Test
    void scenarioObjectiveStatusMustMatchScene() throws Exception {
        Map<String, Object> raw = rawMap("""
                {"message":"Scenario generated.","count":1,"requestedCount":3,
                 "validatedCount":1,"filteredCount":0,"filteredItems":[],
                 "adjustedCount":0,"adjustedItems":[],
                 "rawCandidateCount":1,"inspectedCount":1,"truncatedCount":0,
                 "scenarioName":"Home","rationale":"Missing core content",
                 "objectiveTargets":{"minDevices":1,"minRules":1,"minSpecs":1},
                 "objectiveStatus":"COMPLETE","objectiveIssues":[],
                 "verificationReady":false,"readinessIssues":[
                   {"code":"NO_SPECIFICATIONS","message":"Add a specification."}],
                 "semanticWarnings":[
                   {"code":"NO_AUTOMATION_RULES","message":"No retained rules."},
                   {"code":"UNREFERENCED_DEVICES","message":"One device is unreferenced."}
                 ],
                 "scene":{"schema":"iot-verify.board-scene","version":6,
                 "templates":[],"devices":[{"id":"device_1","templateName":"Sensor",
                 "label":"Hall sensor","position":{"x":0,"y":0},"width":176,"height":128}],
                 "environmentVariables":[],"rules":[],"specs":[]}}
                """);

        assertThrows(BadGatewayException.class,
                () -> parser.parseScenarioRecommendationResponse(raw, validScenarioRequest()));
    }

    private static List<String> iterableFieldNames(JsonNode node) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.sort(String::compareTo);
        return names;
    }

    private static ScenarioRecommendationRequestDto validScenarioRequest() {
        ScenarioRecommendationRequestDto request = new ScenarioRecommendationRequestDto();
        request.setMinDevices(1);
        request.setMinRules(1);
        request.setMinSpecs(1);
        request.setMaxDevices(6);
        request.setMaxRules(5);
        request.setMaxSpecs(5);
        return request;
    }
}
