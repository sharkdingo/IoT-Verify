package cn.edu.nju.Iot_Verify.service.board;

import cn.edu.nju.Iot_Verify.dto.board.BoardUndoResultDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.po.BoardEditJournalPo;
import cn.edu.nju.Iot_Verify.repository.*;
import cn.edu.nju.Iot_Verify.service.impl.BoardStorageServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.time.LocalDateTime;

import static cn.edu.nju.Iot_Verify.service.board.BoardEditUndoFixtures.newRule;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * Board undo and redo against real MySQL, including native JSON inserts and timestamp precision.
 *
 * <p>{@link BoardEditUndoIntegrationTest} exercises undo locally through H2 with text-backed JSON
 * columns. CI requires MySQL for this suite. Local runs without MySQL skip it unless
 * {@code -Diot.verify.mysql.required=true} is set. {@code IOT_VERIFY_UNDO_IT_URL} selects the test server.
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
        journal = BoardEditUndoFixtures.newJournal(journalRepo);
        service = BoardEditUndoFixtures.boardService(
                nodes, environments, specs, rules, templates, users, transactionManager, journal);
        userId = BoardEditUndoFixtures.createUser(users, "13800138201", "undo-mysql-owner");
        BoardEditUndoFixtures.seedLightBoard(templates, nodes, environments, userId);
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
        var before = service.getBoardSnapshot(userId);
        var preview = service.previewNodeDeletion(userId, "light-1");
        assertEquals(before.scene().deviceTemplates(), preview.getDeviceTemplates());
        assertEquals(before.editHistory().editHistoryRevision(), preview.getEditHistoryRevision());
        var deletion = service.deleteNodeCascade(userId, "light-1", preview.getImpactToken());
        var after = service.getBoardSnapshot(userId);
        assertEquals(after.scene().deviceTemplates(), deletion.getDeviceTemplates());
        assertEquals(after.editHistory().editHistoryRevision(), deletion.getEditHistoryRevision());
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
        BoardEditUndoFixtures.persistHistoricRuleTimestamp(rules, entityManager, ruleId);
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
            assertEquals(1, journalRepo.findByUserIdOrderBySequenceAsc(userId).stream()
                    .filter(BoardEditJournalPo::isUndone).count());
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
        assertTrue(journalRepo.findByUserIdOrderBySequenceAsc(userId).stream()
                .noneMatch(BoardEditJournalPo::isUndone));
    }

    @Test
    void clearingAndReusingTheFirstSequenceCannotRetargetAnEarlierUndo() {
        var firstEdit = service.addRule(userId, newRule("original"));
        var earlierBoard = service.getBoardSnapshot(userId);
        String staleRevision = earlierBoard.editHistory().editHistoryRevision();
        assertEquals(firstEdit.getEditHistoryRevision(), staleRevision);

        var clearPreview = service.previewBoardEditHistoryClear(userId);
        service.clearBoardEditHistory(userId, clearPreview.getImpactToken());
        var nextEdit = service.addRule(userId, newRule("after clear", "off", "on"));
        var currentBoard = service.getBoardSnapshot(userId);
        assertEquals(nextEdit.getEditHistoryRevision(), currentBoard.editHistory().editHistoryRevision());
        assertTrue(currentBoard.editHistory().canUndo());

        var conflict = assertThrows(ConflictException.class,
                () -> service.applyBoardEdit(userId, true, staleRevision));
        assertEquals("BOARD_EDIT_HISTORY_CHANGED", conflict.getReasonCode());
        assertEquals(currentBoard.scene().rules(), service.getRules(userId));
        assertEquals(currentBoard.editHistory(), service.getBoardSnapshot(userId).editHistory());

        var undone = service.applyBoardEdit(userId, true, currentBoard.editHistory().editHistoryRevision());
        assertTrue(undone.isApplied());
        assertEquals(List.of(firstEdit.getAffectedItem()), undone.getRules());
        assertTrue(undone.isCanRedo());
        assertEquals(undone.getEditHistoryRevision(),
                service.getBoardSnapshot(userId).editHistory().editHistoryRevision());
    }

}
