package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelSemanticsDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationOutcome;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.po.VerificationTaskPo;
import cn.edu.nju.Iot_Verify.service.board.MySqlAvailableCondition;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import cn.edu.nju.Iot_Verify.util.mapper.VerificationTaskMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.hikari.connection-init-sql=SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ExtendWith(MySqlAvailableCondition.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VerificationResultMySqlIntegrationTest {

    @Autowired private VerificationTaskRepository tasks;
    @Autowired private JdbcTemplate jdbc;

    @BeforeAll
    void retainHistoricalModelColumnCollation() {
        // A historical TEXT column need not have the connection's newer MySQL 8 collation.
        jdbc.execute("ALTER TABLE verification_task MODIFY smv_model_content "
                + "TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        assertEquals("utf8mb4_unicode_ci", jdbc.queryForObject(
                "SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'verification_task' AND COLUMN_NAME = 'smv_model_content'", String.class));
    }

    @BeforeEach
    void verifyQueryConnectionCollation() {
        assertEquals("utf8mb4_0900_ai_ci", jdbc.queryForObject("SELECT @@collation_connection", String.class));
    }

    @ParameterizedTest
    @MethodSource("modelContents")
    void bothResultQueriesPreserveModelPresenceAcrossDifferentCollations(String content) {
        VerificationTaskPo run = tasks.saveAndFlush(completedRun(content));

        var task = tasks.findResultViewByIdAndUserId(
                run.getId(), 9L, VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS).orElseThrow();
        var detail = tasks.findCompletedResultView(run.getId(), 9L, VerificationTaskPo.TaskStatus.COMPLETED,
                VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS).orElseThrow();

        assertEquals(run.getId(), task.getId());
        assertEquals(run.getId(), detail.getId());
        assertEquals(content != null && !content.isBlank(), task.getHasSmvModel());
        assertEquals(task.getHasSmvModel(), detail.getHasSmvModel());
        var mapper = new VerificationTaskMapper();
        assertEquals(task.getHasSmvModel(), mapper.toDto(task).isHasSmvModel());
        assertEquals(detail.getHasSmvModel(), mapper.toRunDto(detail, 0).isHasSmvModel());
    }

    @Test
    void bothProjectedDtosStillRejectCorruptPersistedModelContext() {
        VerificationTaskPo run = completedRun("MODULE main\n");
        run.setModelSemanticsJson("{broken");
        tasks.saveAndFlush(run);
        var task = tasks.findResultViewByIdAndUserId(
                run.getId(), 9L, VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS).orElseThrow();
        var detail = tasks.findCompletedResultView(run.getId(), 9L, VerificationTaskPo.TaskStatus.COMPLETED,
                VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS).orElseThrow();
        var mapper = new VerificationTaskMapper();
        assertEquals("modelSemanticsJson", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toDto(task)).getField());
        assertEquals("modelSemanticsJson", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunDto(detail, 0)).getField());
    }

    private VerificationTaskPo completedRun(String content) {
        LocalDateTime now = LocalDateTime.now();
        return VerificationTaskPo.builder()
                .userId(9L).createdAt(now).startedAt(now).completedAt(now)
                .status(VerificationTaskPo.TaskStatus.COMPLETED).progress(100).processingTimeMs(0L)
                .outcome(VerificationOutcome.SATISFIED).violatedSpecCount(0).disabledRuleCount(0).skippedSpecCount(0)
                .modelSemanticsJson(JsonUtils.toJson(ModelSemanticsDto.forRun(AttackScenarioDto.none(), false, 0, 0, 0)))
                .modelSnapshotJson("{\"capturedAt\":\"2026-10-08T09:30:00\",\"deviceCount\":1,\"ruleCount\":0,"
                        + "\"specificationCount\":1,\"environmentVariableCount\":0,"
                        + "\"deviceTemplateCount\":1,\"templatesFrozen\":true}")
                .specResultsJson("[]").generationIssuesJson("[]").checkLogsJson("[]")
                .smvModelContent(content).build();
    }

    @Test
    void absentRowsRemainEmptyRatherThanFailingBeforeOwnershipAndStatusChecks() {
        assertTrue(tasks.findResultViewByIdAndUserId(
                Long.MAX_VALUE, 9L, VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS).isEmpty());
        assertTrue(tasks.findCompletedResultView(Long.MAX_VALUE, 9L, VerificationTaskPo.TaskStatus.COMPLETED,
                VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS).isEmpty());

        VerificationTaskPo cancelled = tasks.saveAndFlush(VerificationTaskPo.builder()
                .userId(9L).createdAt(LocalDateTime.now()).status(VerificationTaskPo.TaskStatus.CANCELLED)
                .smvModelContent("MODULE main\n").build());
        assertTrue(tasks.findResultViewByIdAndUserId(
                cancelled.getId(), 10L, VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS).isEmpty());
        assertTrue(tasks.findCompletedResultView(cancelled.getId(), 9L, VerificationTaskPo.TaskStatus.COMPLETED,
                VerificationTaskRepository.SMV_NON_BLANK_CHARACTERS).isEmpty());
    }

    static Stream<String> modelContents() {
        return Stream.concat(Stream.of(null, "", "\t\n\r ", "\u00A0", "\u2007", "\u202F", "MODULE main\n",
                        "\t \n".repeat(16000), "\t \n".repeat(16000) + "MODULE main\n"),
                IntStream.rangeClosed(0, Character.MAX_VALUE).filter(Character::isWhitespace)
                        .mapToObj(value -> Character.toString((char) value)));
    }
}
