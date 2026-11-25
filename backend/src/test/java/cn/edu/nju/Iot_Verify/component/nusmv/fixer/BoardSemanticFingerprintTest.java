package cn.edu.nju.Iot_Verify.component.nusmv.fixer;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvModelValidator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.PrivacyStateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BoardSemanticFingerprintTest {

    private DeviceVerificationDto device(String varName, String template) {
        DeviceVerificationDto d = new DeviceVerificationDto();
        d.setVarName(varName);
        d.setTemplateName(template);
        return d;
    }

    private SpecificationDto spec(String id, String value) {
        SpecificationDto s = new SpecificationDto();
        s.setId(id);
        s.setName("Safety requirement");
        s.setTemplateId("1");
        SpecConditionDto c = new SpecConditionDto();
        c.setDeviceId("sensor");
        c.setDeviceLabel("sensor");
        c.setTargetType("variable");
        c.setKey("temperature");
        c.setRelation(">");
        c.setValue(value);
        s.setIfConditions(new java.util.ArrayList<>(List.of(c)));
        return s;
    }

    private Map<String, DeviceSmvData> deviceMap(String varName) {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName(varName);
        return Map.of(varName, smv);
    }

    private Map<String, DeviceSmvData> deviceMap(String key, String varName) {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName(varName);
        return Map.of(key, smv);
    }

    private Map<String, DeviceSmvData> deviceMapWithModes(String key, String varName, List<String> modes) {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName(varName);
        smv.setManifest(DeviceManifest.builder().modes(modes).build());
        return Map.of(key, smv);
    }

    private Map<String, DeviceSmvData> deviceMapWithManifest(String key, String varName, DeviceManifest manifest) {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName(varName);
        smv.setManifest(manifest);
        return Map.of(key, smv);
    }

    private String fingerprintWithDeviceModel(DeviceVerificationDto device, DeviceManifest manifest) {
        ObjectMapper mapper = new ObjectMapper();
        DeviceSmvDataFactory factory = new DeviceSmvDataFactory(
                mapper, null, new SmvModelValidator(), new DeviceTemplateSchemaValidator(mapper));
        var devices = List.of(device);
        return BoardSemanticFingerprint.of(devices, List.of(), factory.buildDeviceSmvMapFromTemplateSnapshots(
                devices, Map.of(device.getTemplateName(), manifest)));
    }

    @Test
    void identicalBoards_produceEqualFingerprint() {
        Map<String, DeviceSmvData> map = Map.of();

        // Separately constructed equal boards, not the same two references: calling `of` twice on one
        // input only proves the method is deterministic, which holds even if it ignored its arguments
        // and returned a constant.
        String first = BoardSemanticFingerprint.of(
                List.of(device("sensor", "t1")), List.of(spec("s0", "30")), map);
        String second = BoardSemanticFingerprint.of(
                List.of(device("sensor", "t1")), List.of(spec("s0", "30")), map);

        assertEquals(first, second);
        // …and a board that actually differs must not collide with it, which is what makes the
        // equality above meaningful.
        assertNotEquals(first, BoardSemanticFingerprint.of(
                List.of(device("sensor", "t1")), List.of(spec("s0", "31")), map));
    }

    @Test
    void variableSource_changesTheFingerprint() {
        // The fingerprint gates the fix pipeline's drift check: "is the board still the one this fix was
        // computed for". `environment` and `reported` compile to different identifiers and ask different
        // questions of the same key, so a fingerprint blind to the field would let a fix be applied to a
        // specification whose meaning had changed underneath it.
        SpecificationDto environment = spec("safety-1", "30");
        environment.getIfConditions().get(0).setVariableSource("environment");
        SpecificationDto reported = spec("safety-1", "30");
        reported.getIfConditions().get(0).setVariableSource("reported");

        assertNotEquals(
                BoardSemanticFingerprint.of(List.of(), List.of(environment), Map.of()),
                BoardSemanticFingerprint.of(List.of(), List.of(reported), Map.of()));
    }

    @Test
    void deviceOrder_doesNotAffectFingerprint() {
        List<DeviceVerificationDto> a = List.of(device("sensor", "t1"), device("lamp", "t2"));
        List<DeviceVerificationDto> b = List.of(device("lamp", "t2"), device("sensor", "t1"));
        assertEquals(
                BoardSemanticFingerprint.of(a, List.of(), Map.of()),
                BoardSemanticFingerprint.of(b, List.of(), Map.of()));
    }

    @Test
    void competingSharedAssignmentsIgnoreCapturedDeviceOrder() {
        DeviceManifest.InternalVariable time = DeviceManifest.InternalVariable.builder()
                .name("time").isInside(false).reads(true).lowerBound(0).upperBound(23)
                .naturalChangeRate("1").build();
        DeviceVerificationDto first = device("first_timer", "Early Timer");
        DeviceVerificationDto second = device("second_timer", "Late Timer");
        DeviceVerificationDto lamp = device("lamp", "Light");
        Map<String, DeviceSmvData> map = Map.of(
                "first_timer", timerSmv(first, time, "0"),
                "second_timer", timerSmv(second, time, "6"),
                "lamp", deviceMap("lamp").get("lamp"));
        List<BoardEnvironmentVariableDto> pool = List.of(
                new BoardEnvironmentVariableDto("time", "23", "trusted", "public"));

        String fingerprint = BoardSemanticFingerprint.of(List.of(first, second, lamp), List.of(), pool, map);
        assertEquals(fingerprint,
                BoardSemanticFingerprint.of(List.of(second, first, lamp), List.of(), pool, map));
        assertEquals(fingerprint,
                BoardSemanticFingerprint.of(List.of(lamp, first, second), List.of(), pool, map));
    }

    @Test
    void standaloneSharedDefaultMatchesTheExplicitPoolValue() {
        var writer = device("writer_1", "Light Control");
        var models = deviceMap("writer_1");
        var domain = DeviceManifest.InternalVariable.builder().name("roomLight").isInside(false).reads(false)
                .values(List.of("dark", "bright")).trust("trusted").privacy("public").build();
        models.get("writer_1").getSharedDeclarations().put("roomLight", domain);

        String implicit = BoardSemanticFingerprint.of(List.of(writer), List.of(), List.of(), models);
        assertEquals(implicit, BoardSemanticFingerprint.of(List.of(writer), List.of(), List.of(
                new BoardEnvironmentVariableDto("roomLight", "dark", "trusted", "public")), models));
        assertNotEquals(implicit, BoardSemanticFingerprint.of(List.of(writer), List.of(), List.of(
                new BoardEnvironmentVariableDto("roomLight", "bright", "trusted", "public")), models));
    }

    private DeviceSmvData timerSmv(DeviceVerificationDto timer, DeviceManifest.InternalVariable time, String resetValue) {
        DeviceManifest.Transition reset = DeviceManifest.Transition.builder().name("reset")
                .trigger(DeviceManifest.Trigger.builder().attribute("time").relation("=").value("23").build())
                .assignments(List.of(DeviceManifest.Assignment.builder().attribute("time").value(resetValue).build()))
                .build();
        DeviceSmvData smv = deviceMap(timer.getVarName()).get(timer.getVarName());
        smv.setManifest(DeviceManifest.builder().name(timer.getTemplateName())
                .internalVariables(List.of(time)).transitions(List.of(reset)).build());
        smv.getSharedDeclarations().put("time", time);
        smv.getEnvVariables().put("time", time);
        return smv;
    }

    @Test
    void digitLeadingNameNormalization_snapshotMatchesRawBoard() {
        // Snapshot carries the SMV-safe name (_1lamp); the current board read from storage carries
        // the raw node id (1Lamp). Both must canonicalize to the same fingerprint.
        List<DeviceVerificationDto> snapshot = List.of(device("_1lamp", "t1"));
        List<DeviceVerificationDto> board = List.of(device("1Lamp", "t1"));
        assertEquals(
                BoardSemanticFingerprint.of(snapshot, List.of(), Map.of()),
                BoardSemanticFingerprint.of(board, List.of(), Map.of()));
    }

    @Test
    void quotedNumericValue_matchesUnquoted() {
        // Frontend strips quotes off numeric values before verifying; the fingerprint must treat "30"
        // and 30 as equal so an untouched board is not flagged.
        List<SpecificationDto> quoted = List.of(spec("s0", "\"30\""));
        List<SpecificationDto> unquoted = List.of(spec("s0", "30"));
        assertEquals(
                BoardSemanticFingerprint.of(List.of(), quoted, Map.of()),
                BoardSemanticFingerprint.of(List.of(), unquoted, Map.of()));
    }

    @Test
    void changedSpecValue_changesFingerprint() {
        assertNotEquals(
                BoardSemanticFingerprint.of(List.of(), List.of(spec("s0", "30")), Map.of()),
                BoardSemanticFingerprint.of(List.of(), List.of(spec("s0", "35")), Map.of()));
    }

    @Test
    void specConditionDeviceLabelDoesNotResolveUnknownDeviceId() {
        SpecificationDto idBacked = spec("s0", "30");
        idBacked.getIfConditions().get(0).setDeviceId("ac_1");
        idBacked.getIfConditions().get(0).setDeviceLabel("LivingRoomAC");

        SpecificationDto labelBacked = spec("s0", "30");
        labelBacked.getIfConditions().get(0).setDeviceId("LivingRoomAC");
        labelBacked.getIfConditions().get(0).setDeviceLabel("LivingRoomAC");

        Map<String, DeviceSmvData> map = deviceMap("LivingRoomAC");
        assertNotEquals(
                BoardSemanticFingerprint.of(List.of(), List.of(idBacked), map),
                BoardSemanticFingerprint.of(List.of(), List.of(labelBacked), map));
    }

    @Test
    void digitLeadingSpecReference_doesNotNormalizeInvalidSpecReference() {
        SpecificationDto snapshotSpec = spec("s0", "30");
        snapshotSpec.getIfConditions().get(0).setDeviceId("_1lamp");
        snapshotSpec.getIfConditions().get(0).setDeviceLabel("_1lamp");

        SpecificationDto boardSpec = spec("s0", "30");
        boardSpec.getIfConditions().get(0).setDeviceId("1Lamp");
        boardSpec.getIfConditions().get(0).setDeviceLabel("1Lamp");

        assertNotEquals(
                BoardSemanticFingerprint.of(
                        List.of(device("_1lamp", "t1")),
                        List.of(snapshotSpec),
                        deviceMap("_1lamp", "_1lamp")),
                BoardSemanticFingerprint.of(
                        List.of(device("1Lamp", "t1")),
                        List.of(boardSpec),
                        deviceMap("1Lamp", "_1lamp")));
    }

    @Test
    void addedSpec_changesFingerprint() {
        assertNotEquals(
                BoardSemanticFingerprint.of(List.of(), List.of(spec("s0", "30")), Map.of()),
                BoardSemanticFingerprint.of(List.of(), List.of(spec("s0", "30"), spec("s1", "40")), Map.of()));
    }

    @Test
    void changedDeviceVariable_changesFingerprint() {
        DeviceVerificationDto plain = device("sensor", "t1");
        DeviceVerificationDto edited = device("sensor", "t1");
        edited.setVariables(List.of(new VariableStateDto("threshold", "99", "trusted")));
        assertNotEquals(
                BoardSemanticFingerprint.of(List.of(plain), List.of(), Map.of()),
                BoardSemanticFingerprint.of(List.of(edited), List.of(), Map.of()));
    }

    @Test
    void changedDevicePrivacy_changesFingerprint() {
        DeviceVerificationDto pub = device("sensor", "t1");
        pub.setPrivacies(List.of(new PrivacyStateDto("temperature", "public")));
        DeviceVerificationDto priv = device("sensor", "t1");
        priv.setPrivacies(List.of(new PrivacyStateDto("temperature", "private")));
        assertNotEquals(
                BoardSemanticFingerprint.of(List.of(pub), List.of(), Map.of()),
                BoardSemanticFingerprint.of(List.of(priv), List.of(), Map.of()));
    }

    @Test
    void specificationPropertyScope_changesFingerprint() {
        SpecificationDto stateLabel = spec("property-scope", "private");
        stateLabel.getIfConditions().get(0).setTargetType("privacy");
        stateLabel.getIfConditions().get(0).setPropertyScope("state");
        stateLabel.getIfConditions().get(0).setKey("Mode");

        SpecificationDto variableLabel = spec("property-scope", "private");
        variableLabel.getIfConditions().get(0).setTargetType("privacy");
        variableLabel.getIfConditions().get(0).setPropertyScope("variable");
        variableLabel.getIfConditions().get(0).setKey("Mode");

        assertNotEquals(
                BoardSemanticFingerprint.of(List.of(), List.of(stateLabel), Map.of()),
                BoardSemanticFingerprint.of(List.of(), List.of(variableLabel), Map.of()));
    }

    @Test
    void changedInitialState_changesFingerprint() {
        DeviceVerificationDto off = device("sensor", "t1");
        off.setState("off");
        DeviceVerificationDto on = device("sensor", "t1");
        on.setState("on");
        assertNotEquals(
                BoardSemanticFingerprint.of(List.of(off), List.of(), Map.of()),
                BoardSemanticFingerprint.of(List.of(on), List.of(), Map.of()));
    }

    @Test
    void modeLessDevice_ignoresUiFallbackState() {
        DeviceVerificationDto snapshot = device("smoke", "Smoke Sensor");

        DeviceVerificationDto board = device("smoke", "Smoke Sensor");
        board.setState("Working");
        board.setCurrentStateTrust("trusted");

        Map<String, DeviceSmvData> map = deviceMapWithModes("smoke", "smoke", List.of());

        assertEquals(
                BoardSemanticFingerprint.of(List.of(snapshot), List.of(), map),
                BoardSemanticFingerprint.of(List.of(board), List.of(), map));
    }

    @Test
    void trustCaseAndDefault_normalized() {
        // Explicit "TRUSTED" must equal the default (no trust set → trusted), so casing/defaulting
        // differences between snapshot and board don't misfire.
        DeviceManifest manifest = DeviceManifest.builder().name("t1").modes(List.of("Setting"))
                .initState("auto").workingStates(List.of(DeviceManifest.WorkingState.builder()
                        .name("auto").build())).build();
        DeviceVerificationDto explicit = device("sensor", "t1");
        explicit.setState("auto");
        explicit.setCurrentStateTrust("TRUSTED");
        DeviceVerificationDto defaulted = device("sensor", "t1");
        defaulted.setState("auto");
        assertEquals(fingerprintWithDeviceModel(explicit, manifest), fingerprintWithDeviceModel(defaulted, manifest));
    }

    @Test
    void omittedStateTrust_matchesTemplateWorkingStateTrust() {
        DeviceManifest manifest = DeviceManifest.builder()
                .name("Air Conditioner").initState("auto")
                .modes(List.of("SwitchState"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder()
                                .name("auto")
                                .trust("trusted")
                                .build(),
                        DeviceManifest.WorkingState.builder()
                                .name("cool")
                                .trust("untrusted")
                                .build()))
                .build();

        DeviceVerificationDto implicit = device("ac", "Air Conditioner");
        implicit.setState("cool");
        DeviceVerificationDto explicit = device("ac", "Air Conditioner");
        explicit.setState("cool");
        explicit.setCurrentStateTrust("untrusted");

        assertEquals(
                fingerprintWithDeviceModel(implicit, manifest),
                fingerprintWithDeviceModel(explicit, manifest));
    }

    @Test
    void explicitStateTrustOverride_changesTemplateWorkingStateTrust() {
        DeviceManifest manifest = DeviceManifest.builder()
                .name("Air Conditioner").initState("cool")
                .modes(List.of("SwitchState"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder()
                                .name("cool")
                                .trust("untrusted")
                                .build()))
                .build();

        DeviceVerificationDto implicit = device("ac", "Air Conditioner");
        implicit.setState("cool");
        DeviceVerificationDto override = device("ac", "Air Conditioner");
        override.setState("cool");
        override.setCurrentStateTrust("trusted");

        assertNotEquals(
                fingerprintWithDeviceModel(implicit, manifest),
                fingerprintWithDeviceModel(override, manifest));
    }

    @Test
    void currentStatePrivacyOverride_changesFingerprint() {
        DeviceManifest manifest = DeviceManifest.builder()
                .name("Light").initState("on")
                .modes(List.of("SwitchState"))
                .workingStates(List.of(DeviceManifest.WorkingState.builder()
                        .name("on")
                        .privacy("public")
                        .build()))
                .build();
        DeviceVerificationDto publicState = device("light", "Light");
        publicState.setState("on");
        DeviceVerificationDto privateState = device("light", "Light");
        privateState.setState("on");
        privateState.setCurrentStatePrivacy("private");

        assertNotEquals(
                fingerprintWithDeviceModel(publicState, manifest),
                fingerprintWithDeviceModel(privateState, manifest));
    }

    @Test
    void omittedInternalVariable_matchesGeneratorEffectiveDefault() {
        DeviceManifest manifest = DeviceManifest.builder()
                .internalVariables(List.of(
                        DeviceManifest.InternalVariable.builder()
                                .name("mode")
                                .isInside(true)
                                .values(List.of("idle", "active"))
                                .trust("trusted")
                                .privacy("public")
                                .build(),
                        DeviceManifest.InternalVariable.builder()
                                .name("level")
                                .isInside(true)
                                .lowerBound(0)
                                .upperBound(100)
                                .trust("trusted")
                                .privacy("public")
                                .build()))
                .build();

        DeviceVerificationDto implicit = device("sensor", "t1");
        DeviceVerificationDto explicit = device("sensor", "t1");
        explicit.setVariables(List.of(
                new VariableStateDto("mode", "idle", "trusted"),
                new VariableStateDto("level", "0", "trusted")));
        explicit.setPrivacies(List.of(
                new PrivacyStateDto("mode", "public"),
                new PrivacyStateDto("level", "public")));

        Map<String, DeviceSmvData> map = deviceMapWithManifest("sensor", "sensor", manifest);
        assertEquals(
                BoardSemanticFingerprint.of(List.of(implicit), List.of(), map),
                BoardSemanticFingerprint.of(List.of(explicit), List.of(), map));
    }

    @Test
    void environmentPoolValue_participatesInFingerprint() {
        DeviceManifest manifest = DeviceManifest.builder()
                .internalVariables(List.of(
                        DeviceManifest.InternalVariable.builder()
                                .name("temperature")
                                .isInside(false)
                                .lowerBound(0)
                                .upperBound(100)
                                .trust("trusted")
                                .privacy("public")
                                .build()))
                .build();

        DeviceVerificationDto sensor = device("sensor", "t1");
        Map<String, DeviceSmvData> map = deviceMapWithManifest("sensor", "sensor", manifest);
        assertNotEquals(
                BoardSemanticFingerprint.of(List.of(sensor), List.of(), List.of(), map),
                BoardSemanticFingerprint.of(
                        List.of(sensor),
                        List.of(),
                        List.of(new BoardEnvironmentVariableDto("temperature", "28", "trusted", "public")),
                        map));
    }

    @Test
    void impactedOnlyEnvironmentPoolValue_participatesInFingerprint() {
        DeviceManifest.InternalVariable temperature = DeviceManifest.InternalVariable.builder()
                .name("temperature")
                .isInside(false)
                .lowerBound(0)
                .upperBound(100)
                .trust("trusted")
                .privacy("public")
                .build();
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName("ac");
        smv.setImpactedEnvironmentVariables(Map.of("temperature", temperature));
        Map<String, DeviceSmvData> map = Map.of("ac", smv);
        DeviceVerificationDto actuator = device("ac", "Air Conditioner");

        assertNotEquals(
                BoardSemanticFingerprint.of(
                        List.of(actuator),
                        List.of(),
                        List.of(new BoardEnvironmentVariableDto("temperature", "28", "trusted", "public")),
                        map),
                BoardSemanticFingerprint.of(
                        List.of(actuator),
                        List.of(),
                        List.of(new BoardEnvironmentVariableDto("temperature", "30", "trusted", "public")),
                        map));
    }
}
