package cn.edu.nju.Iot_Verify.service.board;

import cn.edu.nju.Iot_Verify.dto.board.BoardUndoResultDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.po.DeviceNodePo;
import cn.edu.nju.Iot_Verify.po.BoardEnvironmentVariablePo;
import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.*;
import cn.edu.nju.Iot_Verify.service.impl.BoardStorageServiceImpl;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceNodeMapper;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceTemplateMapper;
import cn.edu.nju.Iot_Verify.util.mapper.RuleMapper;
import cn.edu.nju.Iot_Verify.util.mapper.SpecificationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * The undo cases that only a real MySQL can answer.
 *
 * <p>Restoring a deleted rule goes through {@code RuleRepository.insertWithId}, a native insert into
 * a {@code JSON} column. MySQL parses the bound string into JSON and returns it unchanged; H2 stores
 * it as a JSON *string literal* and cannot emulate the MySQL behaviour at all, so these assertions
 * are meaningless there — which is why they lived as {@code @Disabled} siblings in
 * {@link BoardEditUndoIntegrationTest}.
 *
 * <p>Enabled only when a MySQL server is actually reachable, so the H2-only backend CI job skips this
 * class instead of failing. The full-stack E2E job exercises the same path through the real
 * application. Set {@code IOT_VERIFY_UNDO_IT_URL} to point at another server.
 */
@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@org.junit.jupiter.api.extension.ExtendWith(MySqlAvailableCondition.class)
class BoardEditUndoMySqlIntegrationTest {

    @Autowired private DeviceNodeRepository nodes;
    @Autowired private BoardEnvironmentVariableRepository environments;
    @Autowired private RuleRepository rules;
    @Autowired private SpecificationRepository specs;
    @Autowired private DeviceTemplateRepository templates;
    @Autowired private UserRepository users;
    @Autowired private BoardEditJournalRepository journalRepo;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

    private BoardStorageServiceImpl service;
    private Long userId;
    private BoardEditJournal journal;

    @BeforeEach
    void setUp() {
        journalRepo.deleteAll();
        rules.deleteAll();
        specs.deleteAll();
        nodes.deleteAll();
        environments.deleteAll();
        templates.deleteAll();
        users.deleteAll();
        journal = new BoardEditJournal(journalRepo,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        service = new BoardStorageServiceImpl(
                nodes, environments, specs, rules, null, templates, null,
                new TransactionTemplate(transactionManager), null,
                new SpecificationMapper(), new RuleMapper(), new DeviceNodeMapper(), null,
                new DeviceTemplateMapper(), null, users, journal);
        userId = createUser("13800138201", "undo-mysql-owner");
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
                .userId(owner).name("illuminance").value("10")
                .trust("untrusted").privacy("public").build());
    }

    private RuleDto newRule(String description) {
        return newRule(description, "on", "off");
    }

    /** The board rejects two semantically identical rules, so a second fixture must differ. */
    private RuleDto newRule(String description, String triggerState, String action) {
        RuleDto rule = new RuleDto();
        RuleDto.Condition condition = new RuleDto.Condition();
        condition.setDeviceName("light-1");
        condition.setTargetType("state");
        condition.setAttribute("state");
        condition.setRelation("=");
        condition.setValue(triggerState);
        rule.setConditions(List.of(condition));
        RuleDto.Command command = new RuleDto.Command();
        command.setDeviceName("light-1");
        command.setAction(action);
        rule.setCommand(command);
        rule.setRuleString(description);
        return rule;
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
        // The JSON columns survived the native insert as JSON, not as a quoted string.
        assertEquals(1, undone.getRules().get(0).getConditions().size());
        assertEquals("off", undone.getRules().get(0).getCommand().getAction());
    }

    @Test
    void deviceCascadeUndoAndRedoKeepTheOriginalRuleSnapshot() {
        Long ruleId = service.addRule(userId, newRule("r1")).getAffectedItem().getId();
        RuleDto original = persistedRuleWithHistoricTime(ruleId);
        service.deleteNodeCascade(userId, "light-1", service.previewNodeDeletion(userId, "light-1").getImpactToken());
        assertTrue(service.getRules(userId).isEmpty());

        assertEquals(List.of(original), service.undoLastEdit(userId).getRules());
        assertTrue(service.redoLastUndoneEdit(userId).getRules().isEmpty());
        assertEquals(List.of(original), service.undoLastEdit(userId).getRules());
    }

    @Test
    void automaticFixUndoAndRedoKeepTheOriginalRuleSnapshot() {
        Long ruleId = service.addRule(userId, newRule("r1")).getAffectedItem().getId();
        RuleDto original = persistedRuleWithHistoricTime(ruleId);
        service.updateRulesAgainstSnapshot(userId, snapshot -> List.of());
        assertTrue(service.getRules(userId).isEmpty());

        assertEquals(List.of(original), service.undoLastEdit(userId).getRules());
        assertTrue(service.redoLastUndoneEdit(userId).getRules().isEmpty());
        assertEquals(List.of(original), service.undoLastEdit(userId).getRules());
    }

    private RuleDto persistedRuleWithHistoricTime(Long ruleId) {
        var stored = rules.findById(ruleId).orElseThrow();
        stored.setCreatedAt(LocalDateTime.of(2001, 2, 3, 4, 5, 6));
        rules.saveAndFlush(stored);
        entityManager.clear();
        return service.getRules(userId).stream().filter(rule -> rule.getId().equals(ruleId)).findFirst().orElseThrow();
    }

    @Test
    void freshRuleCreateReturnsAndJournalsThePersistedTimestamp() {
        assertRuleCreateMetadata(false);
    }

    @Test
    void ruleCreateAfterUndoReturnsAndJournalsThePersistedTimestamp() {
        assertRuleCreateMetadata(true);
    }

    private void assertRuleCreateMetadata(boolean afterUndo) {
        if (afterUndo) {
            service.addRule(userId, newRule("abandoned create"));
            assertTrue(service.undoLastEdit(userId).isApplied());
            assertEquals(1, journalRepo.countByUserIdAndUndoneTrue(userId));
        }
        LocalDateTime serverTime = LocalDateTime.of(2001, 2, 3, 4, 5, 6, 123456789);
        cn.edu.nju.Iot_Verify.dto.board.CollectionMutationResultDto<RuleDto> created;
        // Fix the server clock, not a client DTO: @PrePersist must still own the new timestamp.
        try (MockedStatic<LocalDateTime> clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(LocalDateTime::now).thenReturn(serverTime);
            created = service.addRule(userId, newRule("precision-sensitive create"));
        }
        entityManager.flush();
        entityManager.clear();
        RuleDto persisted = service.getRules(userId).get(0);
        var entry = journalRepo.findFirstByUserIdOrderBySequenceDesc(userId).orElseThrow();
        RuleDto recorded = journal.readJson(entry.getAfterJson(), RuleDto.class);
        assertEquals(userId, entry.getUserId());
        // userId is @JsonIgnore on RuleDto; the journal row carries its authoritative owner.
        recorded.setUserId(entry.getUserId());
        Number precision = (Number) entityManager.createNativeQuery("""
                select datetime_precision from information_schema.columns
                where table_schema = database() and table_name = 'rules' and column_name = 'created_at'
                """).getSingleResult();
        assertEquals(6, precision.intValue());
        assertEquals(0, persisted.getCreatedAt().getNano() % 1000,
                "The actual MySQL timestamp(6) cannot preserve sub-microsecond clock values");
        assertTrue(service.undoLastEdit(userId).getRules().isEmpty());
        BoardUndoResultDto redone = service.redoLastUndoneEdit(userId);
        entityManager.flush();
        entityManager.clear();

        assertAll(
                () -> assertEquals(persisted, created.getAffectedItem(), "CREATE affectedItem must be persisted metadata"),
                () -> assertEquals(List.of(persisted), created.getCurrentItems(), "CREATE currentItems must be persisted metadata"),
                () -> assertEquals(persisted, recorded, "CREATE journal must record persisted metadata"),
                () -> assertEquals(List.of(persisted), redone.getRules(), "CREATE redo must restore the original full DTO"),
                () -> assertEquals(List.of(persisted), service.getRules(userId), "CREATE redo must persist the original full DTO"));
    }

    @Test
    void redoReappliesTheDeletionAndUndoIsIdempotentPastTheEnd() {
        Long ruleId = service.addRule(userId, newRule("r1")).getAffectedItem().getId();
        service.removeRuleIfUnchanged(userId, ruleId, service.getRules(userId).get(0));
        service.undoLastEdit(userId);

        assertTrue(service.redoLastUndoneEdit(userId).isApplied());
        assertTrue(service.getRules(userId).isEmpty());

        // Walk back past the beginning: each extra press is a no-op, never a double-apply. The second
        // undo reverses the *create* as well, so the board legitimately ends empty.
        service.undoLastEdit(userId);
        service.undoLastEdit(userId);
        BoardUndoResultDto exhausted = service.undoLastEdit(userId);
        assertFalse(exhausted.isApplied());
        assertEquals("NOTHING_TO_APPLY", exhausted.getReasonCode());
        assertTrue(service.getRules(userId).isEmpty());
    }

    @Test
    void aNewEditAfterAnUndoMakesRedoUnreachable() {
        Long ruleId = service.addRule(userId, newRule("r1")).getAffectedItem().getId();
        service.removeRuleIfUnchanged(userId, ruleId, service.getRules(userId).get(0));
        service.undoLastEdit(userId);
        assertEquals(1, service.getRules(userId).size());

        // A new edit invalidates the abandoned redo branch: replaying it would overwrite this edit.
        service.addRule(userId, newRule("r2", "off", "on"));

        BoardUndoResultDto redo = service.redoLastUndoneEdit(userId);
        assertFalse(redo.isApplied());
        assertEquals("NOTHING_TO_APPLY", redo.getReasonCode());
        assertEquals(0, journalRepo.countByUserIdAndUndoneTrue(userId));
    }


}
