package cn.edu.nju.Iot_Verify.service.board;

import cn.edu.nju.Iot_Verify.dto.board.BoardUndoResultDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.CollectionMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentVariableUpdateRequestDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceLayoutDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceJournalSnapshot;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceRuntimeConfigDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceRuntimeUpdateDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.exception.UnauthorizedException;
import cn.edu.nju.Iot_Verify.po.BoardEditJournalPo;
import cn.edu.nju.Iot_Verify.po.BoardEditOperation;
import cn.edu.nju.Iot_Verify.po.BoardEnvironmentVariablePo;
import cn.edu.nju.Iot_Verify.po.DeviceNodePo;
import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import cn.edu.nju.Iot_Verify.po.RulePo;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.*;
import cn.edu.nju.Iot_Verify.service.impl.BoardStorageServiceImpl;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceNodeMapper;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceTemplateMapper;
import cn.edu.nju.Iot_Verify.util.mapper.RuleMapper;
import cn.edu.nju.Iot_Verify.util.mapper.SpecificationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Undo/redo of board edits against a real database.
 *
 * Covers what only a live persistence layer can show: that the journal entry and the edit commit
 * together, that a restored rule keeps its original id, that repeating an exhausted undo is
 * idempotent, and that a competing change is refused instead of silently discarded.
 */
@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // MySQL compatibility mode so H2 stores a JSON column the way production does. Without it
        // H2 wraps the inserted string as a JSON string literal and the rule cannot be read back.
        // Overridden explicitly: with Replace.NONE the app's own MySQL driver setting is inherited and
        // rejects this H2 URL.
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.url=jdbc:h2:mem:boardEditUndo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
// Replace.NONE so the `spring.datasource.url` above is actually honoured. With the default
// (Replace.ANY) Spring Boot substitutes its own auto-configured embedded DataSource and silently
// discards that URL — losing MODE=MySQL, so every rule/spec read back through the mappers failed with
// PersistedDataIntegrityException on the JSON columns.
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BoardEditUndoIntegrationTest {

    @Autowired private DeviceNodeRepository nodes;
    @Autowired private BoardEnvironmentVariableRepository environments;
    @Autowired private RuleRepository rules;
    @Autowired private SpecificationRepository specs;
    @Autowired private DeviceTemplateRepository templates;
    @Autowired private UserRepository users;
    @Autowired private BoardEditJournalRepository journalRepo;
    @Autowired private AiSessionStateRepository aiSessionStates;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private BoardStorageServiceImpl service;
    private BoardEditJournal journal;
    private Long userId;
    private Long otherUserId;

    /**
     * Re-types the MySQL {@code JSON} columns as text for this H2 schema.
     *
     * <p>The entities declare {@code columnDefinition = "JSON"} and hold a Java {@code String}. MySQL
     * parses that string into JSON and returns it unchanged; H2 — even under {@code MODE=MySQL} — stores
     * it as a JSON *string literal*, so reading it back yields a quoted scalar where the mappers expect
     * an array, and every rule/spec read fails closed with {@code PersistedDataIntegrityException}.
     * H2 cannot emulate the MySQL behaviour, so the column type is what has to change here. This is a
     * schema-shape workaround only: the JSON text written and read is exactly what production handles,
     * which is what makes these assertions meaningful — including the native-insert restore, which
     * needed no {@code @Disabled} once the column stopped re-quoting its value.
     * {@link BoardEditUndoMySqlIntegrationTest} re-asserts that path against a real MySQL, where the
     * server's own JSON parsing is what returns the value unchanged.
     */
    private void relaxJsonColumnsForH2() {
        Map<String, List<String>> jsonColumns = Map.of(
                "device_node", List.of("variables_json", "privacies_json"),
                "rules", List.of("conditions_json", "command_json"),
                "specification", List.of("a_conditions", "if_conditions", "then_conditions", "devices_json"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (Map.Entry<String, List<String>> table : jsonColumns.entrySet()) {
                for (String column : table.getValue()) {
                    statement.executeUpdate("ALTER TABLE " + table.getKey()
                            + " ALTER COLUMN " + column + " SET DATA TYPE CLOB");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not relax H2 JSON columns for this fixture", e);
        }
    }

    @BeforeEach
    void setUp() {
        relaxJsonColumnsForH2();
        // Match Spring's configured mapper: DTOs carry LocalDateTime, which the bare mapper
        // cannot write.
        journal = new BoardEditJournal(journalRepo,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        service = new BoardStorageServiceImpl(
                nodes, environments, specs, rules, null, templates, null,
                new TransactionTemplate(transactionManager), null,
                new SpecificationMapper(), new RuleMapper(), new DeviceNodeMapper(), null,
                new DeviceTemplateMapper(), null, users, journal);
        userId = createUser("13800138001", "undo-owner");
        otherUserId = createUser("13800138002", "undo-other");
        seedDevice(userId);
    }

    private Long createUser(String phone, String username) {
        UserPo user = new UserPo();
        user.setPhone(phone);
        user.setUsername(username);
        user.setPassword("hashed");
        user.setCreatedAt(java.time.LocalDateTime.now());
        return users.saveAndFlush(user).getId();
    }

    /**
     * A rule needs a device to reference, and a device needs its template manifest. The shipped
     * Light manifest is reused so this fixture cannot drift from the real validation rules.
     */
    private void seedDevice(Long owner) {
        String manifest;
        try (var stream = getClass().getResourceAsStream("/deviceTemplate/Light.json")) {
            manifest = new String(java.util.Objects.requireNonNull(stream).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Light device template fixture is unavailable", e);
        }
        templates.saveAndFlush(DeviceTemplatePo.builder()
                .userId(owner)
                .name("Light")
                .defaultTemplate(true)
                .manifestJson(manifest)
                .build());
        nodes.saveAndFlush(DeviceNodePo.builder()
                .id("light-1")
                .userId(owner)
                .templateName("Light")
                .label("Light 1")
                .posX(0.0).posY(0.0).state("on").width(176).height(128)
                .build());
        environments.saveAndFlush(BoardEnvironmentVariablePo.builder()
                .userId(owner)
                .name("illuminance")
                .value("10")
                .trust("untrusted")
                .privacy("public")
                .build());
    }

    private DeviceNodeDto newDevice(String id, String label, double x) {
        DeviceNodeDto device = new DeviceNodeDto();
        device.setId(id);
        device.setTemplateName("Light");
        device.setLabel(label);
        DeviceNodeDto.Position position = new DeviceNodeDto.Position();
        position.setX(x);
        position.setY(20.0);
        device.setPosition(position);
        device.setState("on");
        device.setWidth(176);
        device.setHeight(128);
        return device;
    }

    @Test
    void batchDeviceCreationIsOneEditAndRoundTripsItsEnvironmentPatch() {
        DeviceNodeDto second = newDevice("light-2", "Light 2", 100.0);
        DeviceNodeDto third = newDevice("light-3", "Light 3", 200.0);
        BoardEnvironmentVariableDto patchedEnvironment = new BoardEnvironmentVariableDto(
                "illuminance", "37", "trusted", "private");

        var created = service.addNodes(
                userId, List.of(second, third), List.of(patchedEnvironment));

        assertEquals(3, created.getCurrentNodes().size());
        assertEquals("37", service.getEnvironmentVariables(userId).get(0).getValue());
        assertEquals(1, journalRepo.countByUserId(userId),
                "one batch request must be one undoable user action");

        BoardUndoResultDto undone = service.undoLastEdit(userId);
        assertTrue(undone.isApplied());
        assertEquals(List.of("light-1"), service.getNodes(userId).stream()
                .map(DeviceNodeDto::getId).toList());
        assertEquals(new BoardEnvironmentVariableDto(
                "illuminance", "10", "untrusted", "public"),
                service.getEnvironmentVariables(userId).get(0));

        BoardUndoResultDto redone = service.redoLastUndoneEdit(userId);
        assertTrue(redone.isApplied());
        assertEquals(List.of("light-1", "light-2", "light-3"), service.getNodes(userId).stream()
                .map(DeviceNodeDto::getId).toList());
        assertEquals(patchedEnvironment, service.getEnvironmentVariables(userId).get(0));
    }

    @Test
    void deviceJournalRejectsAnOutOfRangeRecordedPositionWithoutPartiallyRestoring() throws Exception {
        service.addNodes(userId, List.of(
                newDevice("light-2", "Light 2", 100.0),
                newDevice("light-3", "Light 3", 200.0)), List.of());
        assertTrue(service.undoLastEdit(userId).isApplied());

        BoardEditJournalPo entry = journal.nextToRedo(userId).orElseThrow();
        DeviceJournalSnapshot target = journal.readJson(
                entry.getAfterJson(), DeviceJournalSnapshot.class);
        target.getDevices().get(1).setPosition(99);
        entry.setAfterJson(new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .writeValueAsString(target));
        journalRepo.saveAndFlush(entry);

        assertThrows(ConflictException.class, () -> service.redoLastUndoneEdit(userId));

        assertEquals(List.of("light-1"), service.getNodes(userId).stream()
                .map(DeviceNodeDto::getId).toList(),
                "the transaction must roll back the earlier valid insertion too");
        assertTrue(journal.availability(userId).canRedo(),
                "an invalid compound entry must remain unconsumed");
    }

    @Test
    void targetedDeviceCreationUsesTheSameJournalAsManualCreation() {
        service.createNode(userId, current -> newDevice("ai-light", "AI Light", 100.0));

        BoardEditJournalPo entry = journal.nextToUndo(userId).orElseThrow();
        assertEquals("DEVICE", entry.getEntityType().name());
        assertEquals("CREATE", entry.getOperation().name());
        assertTrue(service.undoLastEdit(userId).isApplied());
        assertFalse(nodes.existsByUserIdAndId(userId, "ai-light"));
        assertTrue(service.redoLastUndoneEdit(userId).isApplied());
        assertTrue(nodes.existsByUserIdAndId(userId, "ai-light"));
    }

    @Test
    void layoutUpdateIsOneEditAndNoOpLayoutCreatesNoHistory() {
        DeviceLayoutDto moved = layout(90.0, 45.0, 190, 140);

        var updated = service.updateNodeLayout(userId, "light-1", moved);

        assertEquals("updated", updated.getOperation());
        assertEquals(Boolean.TRUE, updated.getCanUndo());
        assertEquals(Boolean.FALSE, updated.getCanRedo());
        assertEquals(1, journalRepo.countByUserId(userId));
        assertEquals(90.0, service.getNodes(userId).get(0).getPosition().getX());
        DeviceJournalSnapshot recordedAfter = journal.readJson(
                journal.nextToUndo(userId).orElseThrow().getAfterJson(),
                DeviceJournalSnapshot.class);
        assertEquals(recordedAfter.getDevices().get(0).getDevice(), service.getNodes(userId).get(0),
                "the journal must record the exact authoritative post-update device");

        assertTrue(service.undoLastEdit(userId).isApplied());
        assertEquals(0.0, service.getNodes(userId).get(0).getPosition().getX());
        assertTrue(service.redoLastUndoneEdit(userId).isApplied());
        assertEquals(90.0, service.getNodes(userId).get(0).getPosition().getX());

        clearHistory();
        var unchanged = service.updateNodeLayout(userId, "light-1", moved);
        assertEquals("unchanged", unchanged.getOperation());
        assertNull(unchanged.getCanUndo());
        assertNull(unchanged.getCanRedo());
        assertEquals(0, journalRepo.countByUserId(userId));
    }

    private DeviceLayoutDto layout(double x, double y, int width, int height) {
        DeviceLayoutDto layout = new DeviceLayoutDto();
        DeviceNodeDto.Position position = new DeviceNodeDto.Position();
        position.setX(x);
        position.setY(y);
        layout.setPosition(position);
        layout.setWidth(width);
        layout.setHeight(height);
        return layout;
    }

    @Test
    void runtimeUpdateRoundTripsAsOneDeviceEdit() {
        DeviceRuntimeConfigDto expected = new DeviceRuntimeConfigDto();
        expected.setState("on");
        DeviceRuntimeConfigDto desired = new DeviceRuntimeConfigDto();
        desired.setState("off");

        var updated = service.updateNodeRuntime(
                userId, "light-1", new DeviceRuntimeUpdateDto(expected, desired));

        assertEquals("updated", updated.getOperation());
        assertEquals(Boolean.TRUE, updated.getCanUndo());
        assertEquals("off", service.getNodes(userId).get(0).getState());
        assertTrue(service.undoLastEdit(userId).isApplied());
        assertEquals("on", service.getNodes(userId).get(0).getState());
        assertTrue(service.redoLastUndoneEdit(userId).isApplied());
        assertEquals("off", service.getNodes(userId).get(0).getState());
    }

    @Test
    void renameUndoRestoresDeviceAndSpecificationDisplayCaches() {
        service.addSpec(userId, newSpec("on"));
        long beforeRenameHistory = journalRepo.countByUserId(userId);

        var renamed = service.renameNode(userId, "light-1", "Kitchen Light", "Light 1");

        assertEquals(Boolean.TRUE, renamed.getCanUndo());
        assertEquals("Kitchen Light", service.getNodes(userId).get(0).getLabel());
        assertEquals("Kitchen Light", service.getSpecs(userId).get(0)
                .getDevices().get(0).getDeviceLabel());
        assertEquals(beforeRenameHistory + 1, journalRepo.countByUserId(userId));

        assertTrue(service.undoLastEdit(userId).isApplied());
        assertEquals("Light 1", service.getNodes(userId).get(0).getLabel());
        assertEquals("Light 1", service.getSpecs(userId).get(0)
                .getDevices().get(0).getDeviceLabel());
        assertTrue(service.redoLastUndoneEdit(userId).isApplied());
        assertEquals("Kitchen Light", service.getSpecs(userId).get(0)
                .getDevices().get(0).getDeviceLabel());

        long historyAfterRename = journalRepo.countByUserId(userId);
        assertThrows(BadRequestException.class,
                () -> service.renameNode(userId, "light-1", "Kitchen Light", "Kitchen Light"));
        assertEquals(historyAfterRename, journalRepo.countByUserId(userId));
    }

    @Test
    void directEnvironmentEditRoundTripsAndAnUnchangedPatchCreatesNoHistory() {
        EnvironmentVariableUpdateRequestDto update = new EnvironmentVariableUpdateRequestDto(
                "illuminance",
                new EnvironmentVariableUpdateRequestDto.ExpectedValue(
                        "10", "untrusted", "public"),
                new EnvironmentVariableUpdateRequestDto.DesiredPatch(
                        "37", "trusted", "private"));

        EnvironmentMutationResultDto changed =
                service.saveEnvironmentVariables(userId, List.of(update));

        assertEquals("updated", changed.getOperation());
        assertEquals(Boolean.TRUE, changed.getCanUndo());
        assertEquals(Boolean.FALSE, changed.getCanRedo());
        assertEquals("37", service.getEnvironmentVariables(userId).get(0).getValue());
        assertTrue(service.undoLastEdit(userId).isApplied());
        assertEquals("10", service.getEnvironmentVariables(userId).get(0).getValue());
        assertTrue(service.redoLastUndoneEdit(userId).isApplied());
        assertEquals("37", service.getEnvironmentVariables(userId).get(0).getValue());

        clearHistory();
        EnvironmentVariableUpdateRequestDto noOp = new EnvironmentVariableUpdateRequestDto(
                "illuminance",
                new EnvironmentVariableUpdateRequestDto.ExpectedValue(
                        "37", "trusted", "private"),
                new EnvironmentVariableUpdateRequestDto.DesiredPatch("37", null, null));
        EnvironmentMutationResultDto unchanged =
                service.saveEnvironmentVariables(userId, List.of(noOp));
        assertEquals("unchanged", unchanged.getOperation());
        assertNull(unchanged.getCanUndo());
        assertEquals(0, journalRepo.countByUserId(userId));
    }

    @Test
    void environmentJournalRejectsUnsupportedMetadataWithoutChangingThePool() {
        EnvironmentVariableUpdateRequestDto update = new EnvironmentVariableUpdateRequestDto(
                "illuminance",
                new EnvironmentVariableUpdateRequestDto.ExpectedValue(
                        "10", "untrusted", "public"),
                new EnvironmentVariableUpdateRequestDto.DesiredPatch(
                        "37", "trusted", "private"));
        service.saveEnvironmentVariables(userId, List.of(update));
        BoardEditJournalPo entry = journal.nextToUndo(userId).orElseThrow();
        entry.setOperation(BoardEditOperation.DELETE);
        journalRepo.saveAndFlush(entry);

        assertThrows(ConflictException.class, () -> service.undoLastEdit(userId));

        assertEquals("37", service.getEnvironmentVariables(userId).get(0).getValue());
        assertTrue(journal.availability(userId).canUndo(),
                "an invalid Environment Pool entry must remain unconsumed");
    }

    @Test
    void clearingHistoryChangesNoBoardStateAndDisablesBothDirections() {
        service.updateNodeLayout(userId, "light-1", layout(50.0, 20.0, 176, 128));
        assertTrue(journal.availability(userId).canUndo());
        DeviceNodeDto beforeClear = service.getNodes(userId).get(0);

        BoardUndoResultDto cleared = clearHistory();

        assertFalse(cleared.isApplied());
        assertEquals("HISTORY_CLEARED", cleared.getReasonCode());
        assertFalse(cleared.isCanUndo());
        assertFalse(cleared.isCanRedo());
        assertEquals(beforeClear, service.getNodes(userId).get(0));
        assertEquals(0, journalRepo.countByUserId(userId));
    }

    @Test
    void clearingHistoryRejectsAConfirmationThatPredatesANewerEdit() {
        service.updateNodeLayout(userId, "light-1", layout(50.0, 20.0, 176, 128));
        var stalePreview = service.previewBoardEditHistoryClear(userId);

        DeviceRuntimeConfigDto expected = new DeviceRuntimeConfigDto();
        expected.setState("on");
        DeviceRuntimeConfigDto desired = new DeviceRuntimeConfigDto();
        desired.setState("off");
        service.updateNodeRuntime(
                userId, "light-1", new DeviceRuntimeUpdateDto(expected, desired));

        assertThrows(ConflictException.class,
                () -> service.clearBoardEditHistory(userId, stalePreview.getImpactToken()));

        assertEquals(2, journalRepo.countByUserId(userId));
        assertTrue(journal.availability(userId).canUndo());
        assertEquals("off", service.getNodes(userId).get(0).getState());
    }

    private BoardUndoResultDto clearHistory() {
        var preview = service.previewBoardEditHistoryClear(userId);
        return service.clearBoardEditHistory(userId, preview.getImpactToken());
    }

    @Test
    void deviceJournalRejectsANoOpTransitionWithoutMarkingItUndone() {
        service.createNode(userId, current -> newDevice("ai-light", "AI Light", 100.0));
        BoardEditJournalPo entry = journal.nextToUndo(userId).orElseThrow();
        entry.setBeforeJson(entry.getAfterJson());
        journalRepo.saveAndFlush(entry);

        assertThrows(ConflictException.class, () -> service.undoLastEdit(userId));
        assertTrue(nodes.existsByUserIdAndId(userId, "ai-light"));
        assertTrue(journal.availability(userId).canUndo(),
                "an invalid transition must leave the entry retryable");
    }

    @Test
    void deviceUpdateJournalCannotRewriteTheDeviceTemplate() throws Exception {
        String originalTemplate = service.getNodes(userId).get(0).getTemplateName();
        service.updateNodeLayout(userId, "light-1", layout(50.0, 20.0, 176, 128));
        BoardEditJournalPo entry = journal.nextToUndo(userId).orElseThrow();
        DeviceJournalSnapshot before = journal.readJson(
                entry.getBeforeJson(), DeviceJournalSnapshot.class);
        before.getDevices().get(0).getDevice().setTemplateName("tampered-template");
        entry.setBeforeJson(new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .writeValueAsString(before));
        journalRepo.saveAndFlush(entry);

        assertThrows(ConflictException.class, () -> service.undoLastEdit(userId));

        assertEquals(originalTemplate, service.getNodes(userId).get(0).getTemplateName());
        assertEquals(50.0, service.getNodes(userId).get(0).getPosition().getX());
        assertTrue(journal.availability(userId).canUndo(),
                "an invalid update must remain unconsumed");
    }

    @Test
    void ruleJournalRejectsAnUnsupportedOperationWithoutDeletingTheRule() {
        Long ruleId = service.addRule(userId, newRule("r1")).getAffectedItem().getId();
        BoardEditJournalPo entry = journal.nextToUndo(userId).orElseThrow();
        entry.setOperation(BoardEditOperation.UPDATE);
        journalRepo.saveAndFlush(entry);

        assertThrows(ConflictException.class, () -> service.undoLastEdit(userId));

        assertEquals(List.of(ruleId), ruleIdsInOrder());
        assertTrue(journal.availability(userId).canUndo(),
                "an unsupported operation must not consume the entry");
    }

    @Test
    void deviceDeletionRoundTripsItsCascadeIdsOrderAndEnvironment() {
        Long ruleId = service.addRule(userId, newRule("r1")).getAffectedItem().getId();
        RuleDto originalRule = persistedRuleWithHistoricTime(ruleId);
        String specId = service.addSpec(userId, newSpec("on")).getAffectedItem().getId();
        var preview = service.previewNodeDeletion(userId, "light-1");

        service.deleteNodeCascade(userId, "light-1", preview.getImpactToken());
        assertTrue(service.getNodes(userId).isEmpty());
        assertTrue(service.getRules(userId).isEmpty());
        assertTrue(service.getSpecs(userId).isEmpty());
        assertTrue(service.getEnvironmentVariables(userId).isEmpty());

        BoardUndoResultDto undone = service.undoLastEdit(userId);
        assertTrue(undone.isApplied());
        assertEquals(List.of("light-1"), undone.getNodes().stream().map(DeviceNodeDto::getId).toList());
        assertEquals(List.of(ruleId), undone.getRules().stream().map(RuleDto::getId).toList());
        assertEquals(originalRule, undone.getRules().get(0));
        assertEquals(List.of(specId), undone.getSpecs().stream().map(SpecificationDto::getId).toList());
        assertEquals("10", undone.getEnvironmentVariables().get(0).getValue());
        DeviceJournalSnapshot restoredSnapshot = journal.readJson(
                journal.nextToRedo(userId).orElseThrow().getBeforeJson(), DeviceJournalSnapshot.class);
        assertEquals(restoredSnapshot.getDevices().get(0).getDevice(), undone.getNodes().get(0));
        assertEquals(restoredSnapshot.getEnvironmentVariables(), undone.getEnvironmentVariables());

        BoardUndoResultDto redone = service.redoLastUndoneEdit(userId);
        assertTrue(redone.isApplied());
        assertTrue(redone.getNodes().isEmpty());
        assertTrue(redone.getRules().isEmpty());
        assertTrue(redone.getSpecs().isEmpty());
        assertTrue(redone.getEnvironmentVariables().isEmpty());
    }

    @Test
    void newDeviceEditAfterUndoDiscardsTheAbandonedDeletionRedo() {
        var preview = service.previewNodeDeletion(userId, "light-1");
        service.deleteNodeCascade(userId, "light-1", preview.getImpactToken());
        service.undoLastEdit(userId);

        DeviceLayoutDto changed = new DeviceLayoutDto();
        DeviceNodeDto.Position position = new DeviceNodeDto.Position();
        position.setX(99.0);
        position.setY(20.0);
        changed.setPosition(position);
        changed.setWidth(176);
        changed.setHeight(128);
        service.updateNodeLayout(userId, "light-1", changed);

        assertFalse(service.redoLastUndoneEdit(userId).isApplied());
        assertTrue(nodes.existsByUserIdAndId(userId, "light-1"));
        assertFalse(journal.availability(userId).canRedo(),
                "a new edit after undo must discard the abandoned redo branch");
        assertTrue(journal.availability(userId).canUndo(), "the new layout edit must be undoable");
    }

    /** A minimal rule that passes board validation against the Light template. */
    private RuleDto newRule(String text) {
        return newRule(text, "on", "off");
    }

    /**
     * A rule distinguished by its trigger and action, not just its text: the board rejects two
     * semantically identical rules, so tests holding several rules at once must vary these.
     */
    private RuleDto newRule(String text, String triggerState, String action) {
        RuleDto.Condition trigger = new RuleDto.Condition();
        trigger.setDeviceName("light-1");
        trigger.setTargetType("state");
        trigger.setAttribute("state");
        trigger.setRelation("=");
        trigger.setValue(triggerState);

        RuleDto.Command command = new RuleDto.Command();
        command.setDeviceName("light-1");
        command.setAction(action);

        RuleDto rule = new RuleDto();
        rule.setConditions(List.of(trigger));
        rule.setCommand(command);
        rule.setRuleString(text);
        return rule;
    }

    @Test
    void restoringADeletedSpecificationKeepsItsListPosition() {
        String first = service.addSpec(userId, newSpec("on")).getAffectedItem().getId();
        String second = service.addSpec(userId, newSpec("off")).getAffectedItem().getId();
        assertEquals(List.of(first, second), specIdsInOrder());

        // Delete the *first* one: saveSpecsInternal rewrites list_order from the list index, so a
        // restore that appends would silently move it behind the survivor.
        service.removeSpecIfUnchanged(userId, first, service.getSpecs(userId).stream()
                .filter(spec -> first.equals(spec.getId())).findFirst().orElseThrow());
        assertEquals(List.of(second), specIdsInOrder());

        assertTrue(service.undoLastEdit(userId).isApplied());
        assertEquals(List.of(first, second), specIdsInOrder());
    }

    private List<String> specIdsInOrder() {
        return service.getSpecs(userId).stream().map(SpecificationDto::getId).toList();
    }

    /**
     * A minimal template-1 specification that passes board validation against the Light template.
     * Distinguished by its A-condition state, because the board rejects two identical specifications.
     */
    private SpecificationDto newSpec(String state) {
        SpecificationDto spec = new SpecificationDto();
        // Specification ids are author-supplied strings, not IDENTITY-generated.
        spec.setId("spec-" + state);
        spec.setTemplateId("1");
        // Template 1 ("always") uses A conditions only, so the state is what distinguishes the two
        // fixtures — the board rejects two semantically identical specifications.
        spec.setAConditions(List.of(specCondition("a", state)));
        return spec;
    }

    private SpecConditionDto specCondition(String side, String value) {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setDeviceId("light-1");
        condition.setTargetType("state");
        condition.setKey("state");
        condition.setRelation("=");
        condition.setSide(side);
        condition.setValue(value);
        return condition;
    }

    @Test
    void addingARuleRecordsTheJournalEntryInTheSameCommit() {
        CollectionMutationResultDto<RuleDto> created = service.addRule(userId, newRule("r1"));

        assertEquals(1, journalRepo.countByUserIdAndUndoneFalse(userId));
        // The mutation itself reports availability, so the client never has to guess.
        assertEquals(Boolean.TRUE, created.getCanUndo());
        assertEquals(Boolean.FALSE, created.getCanRedo());
    }

    @Test
    void undoRestoresADeletedRuleUnderItsOriginalId() {
        Long ruleId = service.addRule(userId, newRule("r1")).getAffectedItem().getId();
        RuleDto persisted = persistedRuleWithHistoricTime(ruleId);
        service.removeRuleIfUnchanged(userId, ruleId, persisted);
        assertTrue(service.getRules(userId).isEmpty());

        BoardUndoResultDto undone = service.undoLastEdit(userId);

        assertTrue(undone.isApplied());
        assertEquals(1, undone.getRules().size());
        // The original id must come back: rules and specifications reference it.
        assertEquals(ruleId, undone.getRules().get(0).getId());
        assertEquals(persisted, undone.getRules().get(0));
        assertTrue(undone.isCanRedo());
    }

    /**
     * Unlike the two tests above, this one is not blocked by H2's JSON handling: the guard refuses
     * before {@code insertWithId} runs, so no JSON column is ever written.
     */
    @Test
    void undoRefusesWhenAnotherAccountNowHoldsTheDeletedRulesId() {
        Long ruleId = service.addRule(userId, newRule("r1")).getAffectedItem().getId();
        service.removeRuleIfUnchanged(userId, ruleId, service.getRules(userId).get(0));

        // `rules.id` is a single global primary key — not composite with user_id like device_node — so
        // another account can take the freed id. The drift check only inspects this account's rows and
        // therefore sees "absent", which used to send the restore into a primary-key violation.
        rules.insertWithId(ruleId, otherUserId, "[]", "{}", "other account's rule", 0,
                java.time.LocalDateTime.now());

        ConflictException refused = assertThrows(ConflictException.class,
                () -> service.undoLastEdit(userId));
        assertTrue(refused.getMessage().contains("no longer available"),
                "the conflict must name the id collision, not read as generic drift");
    }

    @Test
    void redoReappliesTheDeletionAndUndoIsIdempotentPastTheEnd() {
        Long ruleId = service.addRule(userId, newRule("r1")).getAffectedItem().getId();
        service.removeRuleIfUnchanged(userId, ruleId, service.getRules(userId).get(0));
        service.undoLastEdit(userId);

        assertTrue(service.redoLastUndoneEdit(userId).isApplied());
        assertTrue(service.getRules(userId).isEmpty());

        // Walk back past the beginning: each extra press is a no-op, never a double-apply. The second
        // undo reverses the *create* too, so the board legitimately ends empty — what matters is that
        // the exhausted presses add nothing back.
        service.undoLastEdit(userId);
        service.undoLastEdit(userId);
        BoardUndoResultDto exhausted = service.undoLastEdit(userId);
        assertFalse(exhausted.isApplied());
        assertEquals("NOTHING_TO_APPLY", exhausted.getReasonCode());
        assertFalse(exhausted.isCanUndo());
        assertTrue(service.getRules(userId).isEmpty(), "no duplicate rule was created");
    }

    @Test
    void aRuleAddedAfterDeletionsSortsLastAndItsJournalledPositionMatches() {
        // Deleting a rule does not renumber the survivors, so `execution_order` has gaps. Setting a new
        // rule's order to the list *count* then places it before an existing rule — and execution order
        // decides which rule wins when guards overlap, so this is semantic rather than cosmetic. Two
        // deletions are needed: with one, the id tiebreak hides it.
        // The Light template offers only on/off, so two semantically distinct rules is the maximum on
        // one device. Deleting the first leaves the survivor at execution_order 1 with 0 free, so an
        // added rule taking the list count (1) collides with it — and the id tiebreak then decides.
        Long first = service.addRule(userId, newRule("r1", "on", "off")).getAffectedItem().getId();
        Long second = service.addRule(userId, newRule("r2", "off", "on")).getAffectedItem().getId();
        service.removeRuleIfUnchanged(userId, first, ruleById(first));

        Long added = service.addRule(userId, newRule("r3", "on", "off")).getAffectedItem().getId();

        assertEquals(List.of(second, added), ruleIdsInOrder(),
                "a newly added rule must sort after the rules already on the board");
        // The CREATE entry must describe the position the create actually produced, or undo-then-redo
        // reconstructs a different board than the create did.
        BoardEditJournalPo entry = journal.nextToUndo(userId).orElseThrow();
        assertEquals(String.valueOf(added), entry.getEntityKey());
        assertEquals(1, entry.getEntityOrder(),
                "the journalled position must match the rule's real index");
    }

    private RuleDto ruleById(Long ruleId) {
        return service.getRules(userId).stream()
                .filter(rule -> rule.getId().equals(ruleId))
                .findFirst().orElseThrow();
    }

    private RuleDto persistedRuleWithHistoricTime(Long ruleId) {
        RulePo stored = rules.findById(ruleId).orElseThrow();
        stored.setCreatedAt(LocalDateTime.of(2001, 2, 3, 4, 5, 6));
        rules.saveAndFlush(stored);
        entityManager.clear();
        return ruleById(ruleId);
    }

    @Test
    void anAutomaticFixIsOneReversibleRuleSetEditAndPreservesOlderHistory() {
        Long ruleId = service.addRule(userId, newRule("r1")).getAffectedItem().getId();
        RuleDto originalRule = persistedRuleWithHistoricTime(ruleId);
        assertTrue(journal.availability(userId).canUndo());

        CollectionMutationResultDto<RuleDto> fixed =
                service.updateRulesAgainstSnapshot(userId, snapshot -> List.of());

        assertTrue(service.getRules(userId).isEmpty());
        assertEquals(Boolean.TRUE, fixed.getCanUndo());
        assertEquals(Boolean.FALSE, fixed.getCanRedo());
        assertTrue(journal.availability(userId).canUndo());
        assertFalse(journal.availability(userId).canRedo());
        assertEquals(2, journalRepo.countByUserId(userId),
                "the fix entry must sit above, not erase, the earlier create");

        BoardUndoResultDto undone = service.undoLastEdit(userId);
        assertEquals("RULE_SET", undone.getEntityType());
        assertEquals(List.of(ruleId), ruleIdsInOrder());
        assertEquals(originalRule, ruleById(ruleId));

        BoardUndoResultDto redone = service.redoLastUndoneEdit(userId);
        assertEquals("RULE_SET", redone.getEntityType());
        assertTrue(service.getRules(userId).isEmpty());
    }

    @Test
    void clientCreationTimeCannotOverrideNewOrExistingRuleCreationTime() {
        LocalDateTime supplied = LocalDateTime.of(1999, 1, 2, 3, 4, 5);
        RuleDto input = newRule("r1");
        input.setCreatedAt(supplied);
        Long ruleId = service.addRule(userId, input).getAffectedItem().getId();
        rules.flush();
        entityManager.clear();
        LocalDateTime saved = ruleById(ruleId).getCreatedAt();
        assertNotNull(saved);
        assertNotEquals(supplied, saved);

        service.updateRulesAgainstSnapshot(userId, snapshot -> {
            RuleDto update = newRule("updated description");
            update.setId(ruleId);
            update.setCreatedAt(supplied);
            return List.of(update);
        });
        rules.flush();
        entityManager.clear();
        assertEquals(saved, ruleById(ruleId).getCreatedAt());
    }

    @Test
    void targetedRuleUpdateKeepsAnUntouchedOversizedStoredRuleAndItsUndoHistory() {
        Long legacyId = service.addRule(userId, newRule("legacy")).getAffectedItem().getId();
        Long changedId = service.addRule(userId, newRule("other", "off", "on")).getAffectedItem().getId();
        RulePo legacy = rules.findById(legacyId).orElseThrow();
        legacy.setRuleString("x".repeat(cn.edu.nju.Iot_Verify.dto.RequestLimits.MAX_DESCRIPTION_LENGTH + 1));
        rules.saveAndFlush(legacy);
        entityManager.clear();
        List<RuleDto> original = service.getRules(userId);

        assertDoesNotThrow(() -> service.updateRulesAgainstSnapshot(userId, snapshot -> {
            RuleDto changed = newRule("updated description", "off", "on");
            changed.setId(changedId);
            return List.of(snapshot.rules().get(0), changed);
        }));

        assertEquals(original.get(0), service.getRules(userId).get(0));
        assertEquals(changedId, service.getRules(userId).get(1).getId());
        assertEquals("updated description", service.getRules(userId).get(1).getRuleString());
        assertTrue(service.undoLastEdit(userId).isApplied());
        assertEquals(original, service.getRules(userId));
    }

    @ParameterizedTest
    @CsvSource({"RULE,true", "RULE,false", "DEVICE,true", "DEVICE,false", "RULE_SET,true", "RULE_SET,false"})
    void missingRuleCreationTimeIsUnreadableHistoryInEitherDirection(String kind, boolean undo) throws Exception {
        Long ruleId = service.addRule(userId, newRule("r1")).getAffectedItem().getId();
        RuleDto original = persistedRuleWithHistoricTime(ruleId);
        switch (kind) {
            case "RULE" -> service.removeRuleIfUnchanged(userId, ruleId, original);
            case "DEVICE" -> service.deleteNodeCascade(userId, "light-1",
                    service.previewNodeDeletion(userId, "light-1").getImpactToken());
            default -> service.updateRulesAgainstSnapshot(userId, snapshot -> List.of());
        }
        if (!undo) service.undoLastEdit(userId);
        BoardEditJournalPo entry = (undo ? journal.nextToUndo(userId) : journal.nextToRedo(userId)).orElseThrow();
        ObjectNode before = (ObjectNode) new ObjectMapper().readTree(entry.getBeforeJson());
        ObjectNode snapshotRule = switch (kind) {
            case "RULE" -> before;
            case "DEVICE" -> (ObjectNode) before.path("rules").get(0).path("rule");
            default -> (ObjectNode) before.path("rules").get(0);
        };
        snapshotRule.remove("createdAt");
        entry.setBeforeJson(before.toString());
        journalRepo.saveAndFlush(entry);
        List<DeviceNodeDto> previousNodes = service.getNodes(userId);
        List<RuleDto> previousRules = service.getRules(userId);

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> { if (undo) service.undoLastEdit(userId); else service.redoLastUndoneEdit(userId); });

        assertEquals(409, conflict.getCode());
        assertEquals("This edit can no longer be reversed because its saved details are unreadable.", conflict.getMessage());
        assertEquals(previousNodes, service.getNodes(userId));
        assertEquals(previousRules, service.getRules(userId));
        assertEquals(!undo, journalRepo.findById(entry.getId()).orElseThrow().isUndone(),
                "unreadable history must remain unconsumed");
    }

    @Test
    void automaticFixNoOpAndMalformedHistoryCannotMasqueradeAsApplied() {
        service.addRule(userId, newRule("r1"));
        long beforeNoOp = journalRepo.countByUserId(userId);
        assertThrows(BadRequestException.class,
                () -> service.updateRulesAgainstSnapshot(userId, snapshot -> snapshot.rules()));
        assertEquals(beforeNoOp, journalRepo.countByUserId(userId));

        service.updateRulesAgainstSnapshot(userId, snapshot -> List.of());
        BoardEditJournalPo entry = journal.nextToUndo(userId).orElseThrow();
        entry.setEntityKey("not-the-rule-set");
        journalRepo.saveAndFlush(entry);

        assertThrows(ConflictException.class, () -> service.undoLastEdit(userId));

        assertTrue(service.getRules(userId).isEmpty());
        assertTrue(journal.availability(userId).canUndo(),
                "an invalid automatic-fix entry must remain unconsumed");
    }

    @Test
    void redoOfASpecificationDeleteRemovesItAgain() {
        // Closes the last cell of the {rule, spec, order} x {create, delete} x {undo, redo} matrix.
        // The create/redo cell is where a false "unreadable" conflict hid, so the rest are pinned too.
        service.addSpec(userId, newSpec("on"));
        service.addSpec(userId, newSpec("off"));
        SpecificationDto target = service.getSpecs(userId).get(0);
        service.removeSpecIfUnchanged(userId, target.getId(), target);
        assertEquals(1, service.getSpecs(userId).size());

        assertTrue(service.undoLastEdit(userId).isApplied());
        assertEquals(2, service.getSpecs(userId).size());

        BoardUndoResultDto redone = service.redoLastUndoneEdit(userId);
        assertTrue(redone.isApplied(), "redo must re-apply the deletion");
        assertEquals("REDONE", redone.getReasonCode());
        assertEquals(1, service.getSpecs(userId).size());
    }

    @Test
    void redoOfACreateRestoresTheRuleRatherThanReportingItUnreadable() {
        // CREATE records the position the rule occupied after insertion. Redo must use that position
        // rather than treating the absent before-snapshot as an absent ordering contract.
        Long ruleId = service.addRule(userId, newRule("r1")).getAffectedItem().getId();
        assertTrue(service.undoLastEdit(userId).isApplied());
        assertTrue(service.getRules(userId).isEmpty());

        BoardUndoResultDto redone = service.redoLastUndoneEdit(userId);

        assertTrue(redone.isApplied(), "redo of a create must re-apply it");
        assertEquals("REDONE", redone.getReasonCode());
        assertEquals(1, service.getRules(userId).size());
        assertEquals(ruleId, service.getRules(userId).get(0).getId());
    }

    @Test
    void ruleJournalRejectsAnOutOfRangeRecordedPositionOnRedo() {
        service.addRule(userId, newRule("r1"));
        assertTrue(service.undoLastEdit(userId).isApplied());
        BoardEditJournalPo entry = journal.nextToRedo(userId).orElseThrow();
        entry.setEntityOrder(1);
        journalRepo.saveAndFlush(entry);

        assertThrows(ConflictException.class, () -> service.redoLastUndoneEdit(userId));

        assertTrue(service.getRules(userId).isEmpty());
        assertTrue(journal.availability(userId).canRedo());
    }

    @Test
    void redoOfASpecificationCreateRestoresIt() {
        service.addSpec(userId, newSpec("on"));
        assertTrue(service.undoLastEdit(userId).isApplied());
        assertTrue(service.getSpecs(userId).isEmpty());

        assertTrue(service.redoLastUndoneEdit(userId).isApplied());
        assertEquals(1, service.getSpecs(userId).size());
    }

    @Test
    void specificationJournalRejectsAnOutOfRangeRecordedPositionOnRedo() {
        service.addSpec(userId, newSpec("on"));
        assertTrue(service.undoLastEdit(userId).isApplied());
        BoardEditJournalPo entry = journal.nextToRedo(userId).orElseThrow();
        entry.setEntityOrder(1);
        journalRepo.saveAndFlush(entry);

        assertThrows(ConflictException.class, () -> service.redoLastUndoneEdit(userId));

        assertTrue(service.getSpecs(userId).isEmpty());
        assertTrue(journal.availability(userId).canRedo());
    }

    @Test
    void specificationJournalRejectsAnIdentityMismatchOnRedo() {
        String specId = service.addSpec(userId, newSpec("on")).getAffectedItem().getId();
        assertTrue(service.undoLastEdit(userId).isApplied());
        BoardEditJournalPo entry = journal.nextToRedo(userId).orElseThrow();
        entry.setEntityKey(specId + "-other");
        journalRepo.saveAndFlush(entry);

        assertThrows(ConflictException.class, () -> service.redoLastUndoneEdit(userId));

        assertTrue(service.getSpecs(userId).isEmpty(),
                "a snapshot must not be restored under a different identity");
        assertTrue(journal.availability(userId).canRedo(),
                "an invalid entry must remain unconsumed");
    }

    @Test
    void aNewEditAfterAnUndoMakesRedoUnreachable() {
        Long first = service.addRule(userId, newRule("r1")).getAffectedItem().getId();
        service.removeRuleIfUnchanged(userId, first,
                service.getRules(userId).stream()
                        .filter(rule -> rule.getId().equals(first)).findFirst().orElseThrow());
        service.undoLastEdit(userId);
        assertTrue(journal.availability(userId).canRedo());

        // Redoing the abandoned branch would overwrite this new edit, so it must be discarded.
        service.addRule(userId, newRule("r2", "off", "on"));

        assertFalse(journal.availability(userId).canRedo());
        assertFalse(service.redoLastUndoneEdit(userId).isApplied());
    }

    @Test
    void undoIsRefusedWhenTheRuleChangedAfterTheEdit() {
        Long ruleId = service.addRule(userId, newRule("r1")).getAffectedItem().getId();

        // Something else edits the same rule after the create was journalled. Undoing the create
        // would delete that newer version, so it is refused rather than silently applied.
        rules.findById(ruleId).ifPresent(po -> {
            po.setRuleString("edited elsewhere");
            rules.saveAndFlush(po);
        });

        assertThrows(ConflictException.class, () -> service.undoLastEdit(userId));
        assertEquals(1, service.getRules(userId).size(), "the board was left untouched");
        assertTrue(journal.availability(userId).canUndo(), "the entry stays retryable");
    }

    @Test
    void undoOnlyEverTouchesTheRequestingAccountsHistory() {
        service.addRule(userId, newRule("r1"));

        // A second account has no history of its own and cannot reach the first account's.
        assertFalse(service.undoLastEdit(otherUserId).isApplied());
        assertEquals(1, service.getRules(userId).size());
        assertTrue(journal.availability(userId).canUndo());
    }

    @Test
    void undoRequiresAnActiveUser() {
        service.addRule(userId, newRule("r1"));
        assertThrows(UnauthorizedException.class, () -> service.undoLastEdit(999_999L));
    }

    @Test
    void undoRestoresThePreviousRuleExecutionOrder() {
        Long first = service.addRule(userId, newRule("r1", "on", "off")).getAffectedItem().getId();
        Long second = service.addRule(userId, newRule("r2", "off", "on")).getAffectedItem().getId();

        // Users reach reorder through explicit up/down buttons, so one press is one reversible edit.
        service.reorderRules(userId, List.of(first, second), List.of(second, first));
        assertEquals(List.of(second, first), ruleIdsInOrder());

        assertTrue(service.undoLastEdit(userId).isApplied());
        assertEquals(List.of(first, second), ruleIdsInOrder());

        assertTrue(service.redoLastUndoneEdit(userId).isApplied());
        assertEquals(List.of(second, first), ruleIdsInOrder());
    }

    @Test
    void unchangedRuleOrderIsRejectedWithoutWritingHistory() {
        Long first = service.addRule(userId, newRule("r1", "on", "off")).getAffectedItem().getId();
        Long second = service.addRule(userId, newRule("r2", "off", "on")).getAffectedItem().getId();
        long journalCount = journalRepo.countByUserId(userId);

        assertThrows(BadRequestException.class, () ->
                service.reorderRules(userId, List.of(first, second), List.of(first, second)));

        assertEquals(List.of(first, second), ruleIdsInOrder());
        assertEquals(journalCount, journalRepo.countByUserId(userId),
                "a no-op command must not become undo history");
    }

    @Test
    void ruleOrderJournalRejectsAnUnsupportedOperationWithoutReordering() {
        Long first = service.addRule(userId, newRule("r1", "on", "off")).getAffectedItem().getId();
        Long second = service.addRule(userId, newRule("r2", "off", "on")).getAffectedItem().getId();
        service.reorderRules(userId, List.of(first, second), List.of(second, first));
        BoardEditJournalPo entry = journal.nextToUndo(userId).orElseThrow();
        entry.setOperation(BoardEditOperation.DELETE);
        journalRepo.saveAndFlush(entry);

        assertThrows(ConflictException.class, () -> service.undoLastEdit(userId));

        assertEquals(List.of(second, first), ruleIdsInOrder());
        assertTrue(journal.availability(userId).canUndo(),
                "an invalid reorder entry must remain unconsumed");
    }

    @Test
    void reorderUndoIsRefusedWhenTheRuleSetChangedAfterwards() {
        Long first = service.addRule(userId, newRule("r1", "on", "off")).getAffectedItem().getId();
        Long second = service.addRule(userId, newRule("r2", "off", "on")).getAffectedItem().getId();
        service.reorderRules(userId, List.of(first, second), List.of(second, first));

        // A later rule makes the recorded ordering no longer describe the current rule set, so
        // re-imposing it would silently drop the new rule from the order.
        service.addRule(userId, newRule("r3", "on", "on"));

        // Undo the newer CREATE first, so the reorder entry is the one actually being applied next.
        assertTrue(service.undoLastEdit(userId).isApplied());
        assertEquals(List.of(second, first), ruleIdsInOrder());

        // The reorder is reachable again and its preconditions hold, so reversing it restores the
        // ordering the reorder replaced.
        BoardUndoResultDto reorderUndo = service.undoLastEdit(userId);
        assertTrue(reorderUndo.isApplied());
        assertEquals("RULE_ORDER", reorderUndo.getEntityType());
        assertEquals(List.of(first, second), ruleIdsInOrder());
    }

    private List<Long> ruleIdsInOrder() {
        return service.getRules(userId).stream().map(RuleDto::getId).toList();
    }

    @Test
    void consecutiveRuleDeletionsCanBeUndoneAcrossExecutionOrderGaps() {
        Long first = service.addRule(userId, newRule("r1", "on", "off")).getAffectedItem().getId();
        Long second = service.addRule(userId, newRule("r2", "off", "on")).getAffectedItem().getId();
        service.removeRuleIfUnchanged(userId, first, ruleById(first));
        service.removeRuleIfUnchanged(userId, second, ruleById(second));

        assertTrue(service.undoLastEdit(userId).isApplied());
        assertEquals(List.of(second), ruleIdsInOrder());
        assertTrue(service.undoLastEdit(userId).isApplied());
        assertEquals(List.of(first, second), ruleIdsInOrder());
        assertTrue(service.redoLastUndoneEdit(userId).isApplied());
        assertEquals(List.of(second), ruleIdsInOrder());
        assertTrue(service.redoLastUndoneEdit(userId).isApplied());
        assertTrue(service.getRules(userId).isEmpty());
    }

    @Test
    void creatingASpecificationWithAnExistingIdPreservesTheOriginalAndHistory() {
        SpecificationDto original = service.addSpec(userId, newSpec("on")).getAffectedItem();
        SpecificationDto other = newSpec("off");
        other.setId(original.getId());
        long historyCount = journalRepo.countByUserId(userId);

        assertThrows(ConflictException.class, () -> service.addSpec(userId, other));

        assertEquals(List.of(original), service.getSpecs(userId));
        assertEquals(historyCount, journalRepo.countByUserId(userId));
        assertTrue(service.undoLastEdit(userId).isApplied());
        assertTrue(service.getSpecs(userId).isEmpty());
    }

    @Test
    void sceneReplacementRejectsDuplicateSpecificationIdsBeforeChangingTheBoard() {
        service.addRule(userId, newRule("original"));
        var originalRules = service.getRules(userId);
        SpecificationDto first = newSpec("on");
        SpecificationDto second = newSpec("off");
        second.setId(first.getId());
        var batch = new cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto(
                service.getNodes(userId), service.getEnvironmentVariables(userId), List.of(), List.of(first, second));
        batch.setImpactToken(service.previewBoardReplacement(userId).getImpactToken());
        long historyCount = journalRepo.countByUserId(userId);

        assertThrows(ConflictException.class, () -> service.saveBoardBatch(userId, batch));

        assertEquals(originalRules, service.getRules(userId));
        assertTrue(service.getSpecs(userId).isEmpty());
        assertEquals(historyCount, journalRepo.countByUserId(userId));
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void sceneReplacementAndApplicationStateConsumptionCommitOrRollbackTogether() {
        try {
            service.addRule(userId, newRule("original"));
            var originalRules = service.getRules(userId);
            long historyCount = journalRepo.countByUserId(userId);
            var draft = new cn.edu.nju.Iot_Verify.po.AiSessionStatePo();
            draft.setStateKey("SCENARIO_DRAFT:" + userId + ":atomic-scene");
            draft.setUserId(userId);
            draft.setSessionId("atomic-scene");
            draft.setStateKind(cn.edu.nju.Iot_Verify.component.ai.state.AiSessionStateStore.Kind.SCENARIO_DRAFT);
            draft.setPayloadJson("{}");
            draft.setExpiresAt(java.time.Instant.now().plusSeconds(600));
            var persistedDraft = aiSessionStates.saveAndFlush(draft);
            var batch = new cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto(
                    List.of(newDevice("light-2", "Light 2", 100)),
                    service.getEnvironmentVariables(userId), List.of(), List.of());
            batch.setImpactToken(service.previewBoardReplacement(userId).getImpactToken());

            assertThrows(IllegalStateException.class, () -> service.saveBoardBatch(userId, batch, () -> {
                assertEquals(1, aiSessionStates.deleteByStateKeyAndVersion(
                        persistedDraft.getStateKey(), persistedDraft.getVersion()));
                throw new IllegalStateException("Application state consumption failed");
            }));

            assertEquals(List.of("light-1"), service.getNodes(userId).stream().map(DeviceNodeDto::getId).toList());
            assertEquals(originalRules, service.getRules(userId));
            assertEquals(historyCount, journalRepo.countByUserId(userId));
            assertTrue(aiSessionStates.existsById(persistedDraft.getStateKey()));

            service.saveBoardBatch(userId, batch, () -> assertEquals(1,
                    aiSessionStates.deleteByStateKeyAndVersion(
                            persistedDraft.getStateKey(), persistedDraft.getVersion())));

            assertEquals(List.of("light-2"), service.getNodes(userId).stream().map(DeviceNodeDto::getId).toList());
            assertTrue(service.getRules(userId).isEmpty());
            assertEquals(0, journalRepo.countByUserId(userId));
            assertFalse(aiSessionStates.existsById(persistedDraft.getStateKey()));
        } finally {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                for (Long owner : List.of(userId, otherUserId)) {
                    aiSessionStates.deleteUser(owner);
                    journalRepo.deleteByUserId(owner);
                    rules.deleteByUserId(owner);
                    specs.deleteByUserId(owner);
                    environments.deleteByUserId(owner);
                    nodes.deleteByUserId(owner);
                    templates.deleteByUserId(owner);
                    users.deleteById(owner);
                }
            });
        }
    }

    @Test
    void readingAnUnchangedEnvironmentPoolDoesNotWriteEntities() {
        service.getEnvironmentVariables(userId);
        entityManager.flush();
        Statistics statistics = clearedDatabaseStatistics();

        assertEquals(List.of(new BoardEnvironmentVariableDto("illuminance", "10", "untrusted", "public")),
                service.getEnvironmentVariables(userId));
        entityManager.flush();

        var writes = statistics.getEntityStatistics(BoardEnvironmentVariablePo.class.getName());
        assertEquals(0, writes.getInsertCount());
        assertEquals(0, writes.getDeleteCount());
        assertEquals(0, writes.getUpdateCount());
    }

    @Test
    void addingOneDevicePreservesUnchangedDeviceAndEnvironmentRows() {
        service.addNodes(userId, List.of(newDevice("light-2", "Light 2", 100)), null);
        entityManager.flush();
        Statistics statistics = clearedDatabaseStatistics();

        var result = service.addNodes(userId, List.of(newDevice("light-3", "Light 3", 200)), null);
        entityManager.flush();

        assertEquals(3, result.getCurrentNodes().size());
        var nodeWrites = statistics.getEntityStatistics(DeviceNodePo.class.getName());
        assertEquals(1, nodeWrites.getInsertCount());
        assertEquals(0, nodeWrites.getDeleteCount());
        assertEquals(0, nodeWrites.getUpdateCount());
        var environmentWrites = statistics.getEntityStatistics(BoardEnvironmentVariablePo.class.getName());
        assertEquals(0, environmentWrites.getInsertCount());
        assertEquals(0, environmentWrites.getDeleteCount());
        assertEquals(0, environmentWrites.getUpdateCount());
    }

    @Test
    void addingOneSpecificationPreservesTheUnchangedSpecificationRow() {
        service.addSpec(userId, newSpec("on"));
        entityManager.flush();
        Statistics statistics = clearedDatabaseStatistics();

        var result = service.addSpec(userId, newSpec("off"));
        entityManager.flush();

        assertEquals(2, result.getCurrentCount());
        var writes = statistics.getEntityStatistics(cn.edu.nju.Iot_Verify.po.SpecificationPo.class.getName());
        assertEquals(1, writes.getInsertCount());
        assertEquals(0, writes.getDeleteCount());
        assertEquals(0, writes.getUpdateCount());
    }

    private Statistics clearedDatabaseStatistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        return statistics;
    }

    @Test
    void availabilityIsAQueryThatReportsNoCollectionsAndChangesNothing() {
        service.addRule(userId, newRule("r1"));

        BoardUndoResultDto availability = service.boardEditAvailability(userId);

        assertTrue(availability.isCanUndo());
        assertFalse(availability.isCanRedo());
        assertFalse(availability.isApplied());
        // Availability is a read. Shipping the collections here would invite a client to treat a
        // query response as an authoritative board update.
        assertTrue(availability.getNodes().isEmpty());
        assertTrue(availability.getEnvironmentVariables().isEmpty());
        assertTrue(availability.getRules().isEmpty());
        assertTrue(availability.getSpecs().isEmpty());
        // And it must not consume the history it reports on.
        assertEquals(1, service.getRules(userId).size());
        assertTrue(journal.availability(userId).canUndo());
    }
}
