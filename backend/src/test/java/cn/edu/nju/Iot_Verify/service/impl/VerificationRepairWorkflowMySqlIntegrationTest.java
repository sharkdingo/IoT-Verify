package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.board.PortableSceneRequestParser;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzEngine;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzEngineConfig;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzEngineInput;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzFinding;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize.CounterexampleInitialStateConstraints;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.configure.NusmvConfig;
import cn.edu.nju.Iot_Verify.dto.auth.DeleteAccountRequestDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelSemanticsVersion;
import cn.edu.nju.Iot_Verify.dto.model.RunPersistenceDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationOutcome;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRequestDto;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.service.AuthService;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.service.FixService;
import cn.edu.nju.Iot_Verify.service.UserService;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import cn.edu.nju.Iot_Verify.service.board.MySqlAvailableCondition;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.data.redis.port=1"
})
@ExtendWith(MySqlAvailableCondition.class)
class VerificationRepairWorkflowMySqlIntegrationTest {
    @Autowired private BoardStorageService board;
    @Autowired private VerificationService verification;
    @Autowired private FixService fixes;
    @Autowired private UserService users;
    @Autowired private AuthService auth;
    @Autowired private PortableSceneRequestParser scenes;
    @Autowired private ObjectMapper mapper;
    @Autowired private NusmvConfig nuSmv;
    @Autowired private BoardDataConverter boardDataConverter;
    @Autowired private SmvGenerator smvGenerator;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void savedAuthoredStateReplaysAndItsVerifiedRepairChangesTheBoard() throws Exception {
        assumeTrue(Files.isRegularFile(Path.of(nuSmv.getPath())), "NuSMV executable is required");
        String username = "repair_" + UUID.randomUUID().toString().substring(0, 8);
        String phone = "139" + ThreadLocalRandom.current().nextInt(10000000, 100000000);
        String password = "fixture-password-123!";
        Long userId = users.register(phone, username, password).getId();
        try {
            importLampScene(userId);

            var result = verification.verify(userId, verificationRequest());
            assertEquals(VerificationOutcome.VIOLATED, result.getOutcome());
            assertTrue(result.isModelComplete());
            assertEquals(RunPersistenceDto.Status.SAVED, result.getHistoryPersistence().getStatus());
            Long runId = result.getHistoryPersistence().getRunId();
            var savedRun = verification.getRun(userId, runId);
            assertEquals(ModelSemanticsVersion.CURRENT, savedRun.getModelSnapshot().getModelSemanticsVersion());
            assertEquals(1, savedRun.getCounterexamples().size());
            Long traceId = savedRun.getCounterexamples().get(0).getId();
            var trace = verification.getTrace(userId, traceId);
            assertEquals(trace.getStates(), verification.getRunTraces(userId, runId).get(0).getStates());
            var lampSteps = trace.getStates().stream().map(step -> step.getDevices().stream()
                    .filter(device -> "lamp_1".equals(device.getDeviceId())).findFirst().orElseThrow()).toList();
            var initial = lampSteps.get(0);
            assertEquals("state;FanRate", initial.getMode());
            assertEquals("Case;night standby", initial.getState());
            assertEquals("ready;day ready", lampSteps.get(1).getState());
            assertTrue(initial.getTrustPrivacy().stream().anyMatch(property ->
                    "Case".equals(property.getName()) && "state".equals(property.getMode())));
            assertTrue(initial.getPrivacies().stream().anyMatch(property ->
                    "night standby".equals(property.getName()) && "FanRate".equals(property.getMode())));
            var snapshot = boardDataConverter.getModelInputSnapshot(userId);
            var modelDevices = smvGenerator.buildDeviceSmvMapFromTemplateSnapshots(
                    snapshot.devices(), snapshot.templateManifests());
            var seed = CounterexampleInitialStateConstraints.build(
                    trace.getStates().get(0), snapshot.rules(), modelDevices, AttackScenarioDto.none(), true);
            assertTrue(seed.containsAll(List.of(
                    "lamp_1.state = _Case", "lamp_1.FanRate = nightstandby",
                    "lamp_1.trust_state__Case = trusted", "lamp_1.privacy_FanRate_nightstandby = public")), seed::toString);
            assertEquals(List.of("ready; day ready"), fixes.localizeFault(userId, traceId).getFaultRules().stream()
                    .map(fault -> fault.getTargetEndState()).toList());

            var repairs = fixes.fix(userId, traceId, List.of("remove"), null);
            assertTrue(repairs.isFixable(), () -> String.valueOf(repairs.getStrategyAttempts()));
            var suggestion = repairs.getSuggestions().get(0);
            assertNotNull(suggestion.getSuggestionToken());
            assertEquals(1, board.getRules(userId).size());
            assertRepairRequiresFrozenPool(userId, runId, traceId);

            assertEquals(List.of(1), finiteViolationSteps(userId));
            var originalRule = board.getRules(userId).get(0);
            var editedRule = mapper.convertValue(originalRule, RuleDto.class);
            editedRule.getConditions().get(1).setValue("reviewing, released");
            editedRule.getConditions().get(2).setValue("ready; day ready | Case;");
            board.updateRuleIfUnchanged(userId, originalRule.getId(), originalRule, editedRule);
            var originalSpec = board.getSpecs(userId).get(0);
            var editedSpec = mapper.convertValue(originalSpec, SpecificationDto.class);
            editedSpec.getAConditions().get(1).setValue("reviewing | withheld");
            editedSpec.getAConditions().get(2).setValue("ready; night standby, Case;_");
            board.updateSpecIfUnchanged(userId, originalSpec.getId(), originalSpec, editedSpec);
            assertEquals("ready; day ready | Case;", board.getRules(userId).get(0).getConditions().get(2).getValue());
            assertEquals("ready; night standby, Case;_", board.getSpecs(userId).get(0).getAConditions().get(2).getValue());

            var equivalent = verification.verify(userId, verificationRequest());
            assertEquals(VerificationOutcome.VIOLATED, equivalent.getOutcome());
            assertEquals(result.getModelSnapshot().getModelFingerprint(),
                    equivalent.getModelSnapshot().getModelFingerprint());
            assertEquals(List.of(1), finiteViolationSteps(userId));
            var applied = fixes.applyFix(userId, traceId, "remove", suggestion, suggestion.getSuggestionToken(), null);
            assertTrue(applied.isApplied());
            assertTrue(applied.isVerificationEvidenceReused());
            assertTrue(board.getRules(userId).isEmpty());

            var reverified = verification.verify(userId, verificationRequest());
            assertEquals(VerificationOutcome.SATISFIED, reverified.getOutcome());
            assertTrue(reverified.isModelComplete());
            assertEquals(RunPersistenceDto.Status.SAVED, reverified.getHistoryPersistence().getStatus());
            assertTrue(finiteViolationSteps(userId).isEmpty());
        } finally {
            var cleanup = new DeleteAccountRequestDto();
            cleanup.setPassword(password);
            cleanup.setConfirmation(username);
            auth.deleteAccount(userId, null, cleanup);
        }
    }

    private List<Integer> finiteViolationSteps(Long userId) {
        var input = new FuzzEngineInput(boardDataConverter.getModelInputSnapshot(userId),
                new FuzzEngineConfig(List.of(), 1, 2, 1, 1L));
        var run = new FuzzEngine().run(input, null, () -> false);
        return run.findings().stream().map(FuzzFinding::firstViolationStep).toList();
    }

    private void assertRepairRequiresFrozenPool(Long userId, Long runId, Long traceId) throws Exception {
        String savedRequest = jdbc.queryForObject(
                "SELECT request_json FROM verification_task WHERE id = ?", String.class, runId);
        ObjectNode corrupted = (ObjectNode) mapper.readTree(savedRequest);
        assertEquals(1, corrupted.path("environmentVariables").size());
        assertEquals("21", corrupted.path("environmentVariables").get(0).path("value").asText());
        assertNotNull(corrupted.remove("environmentVariables"));
        try {
            assertEquals(1, jdbc.update("UPDATE verification_task SET request_json = ? WHERE id = ?",
                    mapper.writeValueAsString(corrupted), runId));
            assertFalse(verification.getTrace(userId, traceId).getStates().isEmpty());
            assertThrows(PersistedDataIntegrityException.class, () -> fixes.localizeFault(userId, traceId));
        } finally {
            assertEquals(1, jdbc.update("UPDATE verification_task SET request_json = ? WHERE id = ?",
                    savedRequest, runId));
        }
    }

    private void importLampScene(Long userId) throws Exception {
        var preview = board.previewBoardReplacement(userId);
        var request = mapper.createObjectNode();
        request.put("impactToken", preview.getImpactToken());
        request.set("scene", mapper.readTree("""
                {"schema":"iot-verify.board-scene","version":6,
                 "templates":[{"name":"Desk Lamp","manifest":{
                    "Name":"Desk Lamp","Modes":["state","FanRate"],"InitState":"Case;night standby","InternalVariables":[],
                    "WorkingStates":[{"Name":"Case;night standby","Trust":"trusted","Privacy":"public"},
                                     {"Name":"ready;day ready","Trust":"trusted","Privacy":"public"}],
                    "APIs":[{"Name":"turnOn","StartState":"Case;","EndState":"ready;day ready","Signal":true}]}},
                    {"name":"Permit Gate","manifest":{"Name":"Permit Gate","Modes":["PermitStatus"],
                        "InitState":"released","InternalVariables":[{"Name":"roomTemperature","IsInside":false,
                            "Reads":true,"FalsifiableWhenCompromised":false,"Trust":"untrusted","Privacy":"public",
                            "LowerBound":10,"UpperBound":30,"NaturalChangeRate":"0"}],"WorkingStates":[
                            {"Name":"released","Trust":"trusted","Privacy":"public"},
                            {"Name":"reviewing","Trust":"trusted","Privacy":"public"},
                            {"Name":"withheld","Trust":"trusted","Privacy":"public"}]}}],
                 "devices":[{"id":"lamp_1","templateName":"Desk Lamp","label":"Desk Lamp","state":"Case;night standby",
                             "position":{"x":30,"y":40},"width":170,"height":120},
                            {"id":"permit","templateName":"Permit Gate","label":"Permit Gate","state":"released",
                             "position":{"x":250,"y":40},"width":170,"height":120}],
                 "environmentVariables":[{"name":"roomTemperature","value":"21","trust":"trusted","privacy":"public"}],
                 "rules":[{"name":"Turn on the lamp","toId":"lamp_1","toApi":"turnOn",
                           "sources":[{"fromId":"lamp_1","fromApi":"state","itemType":"mode",
                                       "relation":"=","value":"Case"},
                                      {"fromId":"permit","fromApi":"state","itemType":"state",
                                       "relation":"IN","value":"released;reviewing"},
                                      {"fromId":"lamp_1","fromApi":"state","itemType":"state",
                                       "relation":"IN","value":"Case;_, ready;day ready"}]}],
                 "specs":[{"name":"The desk lamp never lights","templateId":"3","aConditions":[
                          {"deviceId":"lamp_1","targetType":"mode","key":"state","relation":"=","value":"ready"},
                          {"deviceId":"permit","targetType":"state","key":"state","relation":"NOT_IN",
                           "value":"withheld;reviewing"},
                          {"deviceId":"lamp_1","targetType":"state","key":"state","relation":"NOT_IN",
                           "value":"Case;, ready;night standby"}],
                          "ifConditions":[],"thenConditions":[]}]}
                """));
        board.saveBoardBatch(userId, scenes.parse(request));
    }

    private VerificationRequestDto verificationRequest() {
        var request = new VerificationRequestDto();
        request.setAttackScenario(AttackScenarioDto.none());
        request.setEnablePrivacy(true);
        return request;
    }
}
