package cn.edu.nju.Iot_Verify.configure;

import cn.edu.nju.Iot_Verify.po.BoardEditEntityType;
import cn.edu.nju.Iot_Verify.po.BoardEditJournalPo;
import cn.edu.nju.Iot_Verify.po.BoardEditOperation;
import cn.edu.nju.Iot_Verify.po.SpecificationPo;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.BoardEditJournalRepository;
import cn.edu.nju.Iot_Verify.repository.SpecificationRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.service.board.MySqlAvailableCondition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Current entity-generated schema plus the necessary scalar-owner and MySQL collation constraints. */
@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ExtendWith(MySqlAvailableCondition.class)
@Import(DatabaseSchemaConstraints.class)
class FreshSchemaMySqlIntegrationTest {

    @Autowired private UserRepository users;
    @Autowired private SpecificationRepository specifications;
    @Autowired private BoardEditJournalRepository journal;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void usernamesRemainCaseAndAccentSensitiveAfterFreshSchemaCreation() {
        List<String> names = List.of("Alice", "alice", "Álice", "álice");
        for (int index = 0; index < names.size(); index++) {
            user("1380000000" + index, names.get(index));
        }
        for (String name : names) {
            assertEquals(name, users.findByUsername(name).orElseThrow().getUsername());
        }
        assertEquals(DatabaseSchemaConstraints.TARGET_COLLATION, jdbc.queryForObject(
                "SELECT COLLATION_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user' "
                        + "AND COLUMN_NAME = 'username'", String.class));
    }

    @Test
    void exactDuplicateUsernameIsRejected() {
        user("13800000000", "same-user");
        assertThrows(DataIntegrityViolationException.class,
                () -> user("13800000001", "same-user"));
    }

    @Test
    void specificationIdentityIsScopedToItsOwnerFromCreation() {
        UserPo first = user("13800000000", "first");
        UserPo second = user("13800000001", "second");
        specifications.saveAndFlush(specification(first.getId(), "First"));
        specifications.saveAndFlush(specification(second.getId(), "Second"));

        assertEquals("First", specifications.findByUserId(first.getId()).get(0).getTemplateLabel());
        assertEquals("Second", specifications.findByUserId(second.getId()).get(0).getTemplateLabel());
        assertEquals(List.of("id", "user_id"), jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'specification' "
                        + "AND CONSTRAINT_NAME = 'PRIMARY' ORDER BY COLUMN_NAME", String.class));
    }

    @Test
    void journalSequenceUniquenessIsGeneratedByTheEntity() {
        UserPo first = user("13800000000", "first");
        UserPo second = user("13800000001", "second");
        journal.saveAndFlush(entry(first.getId()));
        journal.saveAndFlush(entry(second.getId()));
        assertThrows(DataIntegrityViolationException.class,
                () -> journal.saveAndFlush(entry(first.getId())));
    }

    @Test
    void accountCascadePreservesOtherOwnersAndRejectsWritesAfterDeletion() {
        UserPo first = user("13800000000", "first");
        UserPo second = user("13800000001", "second");
        specifications.saveAndFlush(specification(first.getId(), "First"));
        specifications.saveAndFlush(specification(second.getId(), "Second"));
        journal.saveAndFlush(entry(first.getId()));
        journal.saveAndFlush(entry(second.getId()));

        users.deleteById(first.getId());
        users.flush();

        assertTrue(specifications.findByUserId(first.getId()).isEmpty());
        assertEquals("Second", specifications.findByUserId(second.getId()).get(0).getTemplateLabel());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM board_edit_journal WHERE user_id = ?", Long.class, first.getId()));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM board_edit_journal WHERE user_id = ?", Long.class, second.getId()));
        SpecificationPo lateWrite = specification(first.getId(), "Deleted owner");
        lateWrite.setId("late-spec");
        assertThrows(DataIntegrityViolationException.class,
                () -> specifications.saveAndFlush(lateWrite));
    }

    @Test
    void compactTraceCountsAreRequiredFromCreation() {
        for (String table : List.of("trace", "simulation_trace")) {
            assertEquals("NO", jdbc.queryForObject(
                    "SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                            + "AND COLUMN_NAME = 'state_count'", String.class, table));
        }
    }

    private UserPo user(String phone, String name) {
        return users.saveAndFlush(UserPo.builder()
                .phone(phone).username(name).password("encoded-password").build());
    }

    private SpecificationPo specification(Long owner, String label) {
        return SpecificationPo.builder().name("Safety requirement")
                .id("shared-spec").userId(owner).listOrder(0).templateId("1").templateLabel(label)
                .aConditionsJson("[]").ifConditionsJson("[]").thenConditionsJson("[]")
                .devicesJson("[]").build();
    }

    private BoardEditJournalPo entry(Long owner) {
        return BoardEditJournalPo.builder()
                .userId(owner).sequence(1L).entityType(BoardEditEntityType.RULE)
                .operation(BoardEditOperation.CREATE).entityKey("rule-1").afterJson("{}")
                .createdAt(LocalDateTime.now()).build();
    }
}
