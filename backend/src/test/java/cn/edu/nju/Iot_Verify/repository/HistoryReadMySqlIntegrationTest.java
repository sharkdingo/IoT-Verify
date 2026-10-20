package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.po.BoardEditEntityType;
import cn.edu.nju.Iot_Verify.po.BoardEditJournalPo;
import cn.edu.nju.Iot_Verify.po.BoardEditOperation;
import cn.edu.nju.Iot_Verify.po.FuzzTaskPo;
import cn.edu.nju.Iot_Verify.po.SimulationTaskPo;
import cn.edu.nju.Iot_Verify.po.SimulationTracePo;
import cn.edu.nju.Iot_Verify.po.TracePo;
import cn.edu.nju.Iot_Verify.po.VerificationTaskPo;
import cn.edu.nju.Iot_Verify.service.board.BoardEditJournal;
import cn.edu.nju.Iot_Verify.service.board.BoardUndoAvailability;
import cn.edu.nju.Iot_Verify.service.board.MySqlAvailableCondition;
import cn.edu.nju.Iot_Verify.util.mapper.TraceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=cn.edu.nju.Iot_Verify.repository.HistoryReadMySqlIntegrationTest$SqlCapture"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ExtendWith(MySqlAvailableCondition.class)
class HistoryReadMySqlIntegrationTest {
    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> STATEMENTS = ThreadLocal.withInitial(ArrayList::new);

        @Override
        public String inspect(String sql) {
            STATEMENTS.get().add(sql.toLowerCase(java.util.Locale.ROOT));
            return sql;
        }
    }

    @Autowired private TraceRepository traces;
    @Autowired private VerificationTaskRepository verificationTasks;
    @Autowired private SimulationTaskRepository simulationTasks;
    @Autowired private SimulationTraceRepository simulationTraces;
    @Autowired private BoardEditJournalRepository journalRepository;
    @Autowired private FuzzTaskRepository fuzzTasks;
    @Autowired private EntityManager entityManager;

    @Test
    void traceSummariesAndFrozenSourcesShareOneScopedPayloadFreeQuery() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        String state = "{\"devices\":[{\"deviceId\":\"sensor_1\",\"modelTokenSource\":\"CUSTOM\"}]}";
        String later = "{\"devices\":[{\"deviceId\":\"future_1\",\"modelTokenSource\":\"BUNDLED\"}]}";
        String states = "[" + state + "," + (later + ",").repeat(198) + later + "]";
        TracePo first = traces.saveAndFlush(trace(7L, 41L, states, now));
        TracePo second = traces.saveAndFlush(trace(7L, 41L, states, now));
        traces.saveAndFlush(trace(8L, 41L, states, now.plusSeconds(1)));
        traces.saveAndFlush(trace(7L, 42L, states, now.plusSeconds(1)));
        entityManager.clear();
        SqlCapture.STATEMENTS.get().clear();

        var summaries = traces.findSummariesByUserIdAndVerificationTaskIdIn(7L, List.of(41L));

        assertEquals(2, summaries.size());
        assertTrue(summaries.stream().noneMatch(item -> java.lang.reflect.Proxy.isProxyClass(item.getClass())),
                "repeated trace-field access must use captured scalar values without dynamic proxies");
        assertEquals(Set.of(second.getId(), first.getId()),
                Set.copyOf(summaries.stream().map(item -> item.getId()).toList()));
        assertEquals(now, summaries.get(0).getCreatedAt());
        assertEquals(200, summaries.get(0).getStateCount());
        var mapped = new TraceMapper().toSummaryDto(summaries.get(0), summaries.get(0));
        assertEquals(Map.of("sensor_1", ModelTokenSource.CUSTOM), mapped.getModelTokenSourcesByDeviceId());
        assertEquals("Always", mapped.getViolatedSpec().getTemplateLabel());
        assertTrue(mapped.getDataAvailable());
        var selects = selects();
        assertEquals(1, selects.size());
        assertFalse(selects.get(0).contains("select *"));
        assertFalse(selects.get(0).contains("states_json as"));
        assertFalse(selects.get(0).contains("request_json"));
        assertTrue(selects.get(0).contains("json_extract"));
        assertFalse(selects.get(0).contains("order by"));
    }

    @Test
    void fuzzPresentationMaterializesEachOwnedEnvelopeOnceWithoutReturningTheFrozenModel() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var specifications = List.of(Map.of("id", "spec-1", "templateId", "3"));
        var sources = Map.of("sensor_1", "CUSTOM");
        String envelope = mapper.writeValueAsString(Map.of(
                "schemaVersion", 1,
                "snapshot", Map.of("specifications", specifications, "manifests", "x".repeat(80_000)),
                "modelTokenSourcesByDeviceId", sources));
        var owned = fuzzTasks.saveAndFlush(fuzzTask(7L, envelope));
        var foreign = fuzzTasks.saveAndFlush(fuzzTask(8L, envelope));
        fuzzTasks.saveAndFlush(fuzzTask(7L, envelope));
        entityManager.clear();
        SqlCapture.STATEMENTS.get().clear();

        var presentations = fuzzTasks.findSpecificationPresentationByUserIdAndIdIn(
                7L, List.of(owned.getId(), foreign.getId()));

        assertEquals(1, presentations.size());
        var presentation = presentations.get(0);
        assertEquals(owned.getId(), presentation.getId());
        assertEquals(mapper.valueToTree(specifications), mapper.readTree(presentation.getSpecificationsJson()));
        assertEquals(mapper.valueToTree(sources), mapper.readTree(presentation.getModelTokenSourcesJson()));
        assertTrue(presentation.getSpecificationsJson().length() < 100);
        assertTrue(presentation.getModelTokenSourcesJson().length() < 100);
        var selects = selects();
        assertEquals(1, selects.size());
        String sql = selects.get(0);
        // The large LONGTEXT must be validated once and materialized as JSON before field extraction.
        assertEquals(1, sql.split("json_valid\\(", -1).length - 1);
        assertEquals(1, sql.split("cast\\(model_input_snapshot_json as json\\)", -1).length - 1);
        String plan = entityManager.unwrap(Session.class).doReturningWork(connection -> {
            try (var statement = connection.prepareStatement("EXPLAIN FORMAT=JSON " + sql)) {
                statement.setLong(1, 7L);
                statement.setLong(2, owned.getId());
                statement.setLong(3, foreign.getId());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    return rows.getString(1);
                }
            }
        });
        assertNotNull(mapper.readTree(plan).findValue("materialized_from_subquery"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "not-json", "{", "{}", "[]", "null",
            "{\"schemaVersion\":2,\"snapshot\":{\"specifications\":[]},\"modelTokenSourcesByDeviceId\":{}}" })
    void fuzzPresentationKeepsMalformedAndUnsupportedEnvelopesUnavailable(String envelope) {
        var saved = fuzzTasks.saveAndFlush(fuzzTask(7L, envelope));
        entityManager.clear();

        var presentation = fuzzTasks.findSpecificationPresentationByUserIdAndIdIn(
                7L, List.of(saved.getId())).get(0);

        assertEquals(saved.getId(), presentation.getId());
        assertNull(presentation.getSpecificationsJson());
        assertNull(presentation.getModelTokenSourcesJson());
    }

    @Test
    void fuzzPresentationPreservesMissingFieldsAndExplicitJsonNulls() {
        List<String> envelopes = List.of(
                "{\"schemaVersion\":1}",
                "{\"schemaVersion\":1,\"snapshot\":{\"specifications\":[]}}",
                "{\"schemaVersion\":1,\"modelTokenSourcesByDeviceId\":{}}",
                "{\"schemaVersion\":1,\"snapshot\":{\"specifications\":null},\"modelTokenSourcesByDeviceId\":null}");
        String[][] expected = { { null, null }, { "[]", null }, { null, "{}" }, { "null", "null" } };
        List<FuzzTaskPo> saved = envelopes.stream()
                .map(envelope -> fuzzTasks.saveAndFlush(fuzzTask(7L, envelope))).toList();
        entityManager.clear();

        var presentations = fuzzTasks.findSpecificationPresentationByUserIdAndIdIn(
                7L, saved.stream().map(FuzzTaskPo::getId).toList());

        assertEquals(saved.size(), presentations.size());
        for (int i = 0; i < saved.size(); i++) {
            Long id = saved.get(i).getId();
            var presentation = presentations.stream().filter(row -> row.getId().equals(id)).findFirst().orElseThrow();
            assertEquals(expected[i][0], presentation.getSpecificationsJson());
            assertEquals(expected[i][1], presentation.getModelTokenSourcesJson());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "[]", "[{\"devices\":[]}]",
            "[{\"devices\":[{\"deviceId\":\"sensor_1\"}]}]",
            "[{\"devices\":[{\"deviceId\":\"sensor_1\",\"modelTokenSource\":\"ALIEN\"}]}]" })
    void damagedFrozenSourceMetadataStillFailsClosed(String states) {
        TracePo saved = traces.saveAndFlush(trace(7L, 41L, states, LocalDateTime.now()));
        entityManager.clear();
        var projection = traces.findSummariesByUserIdAndVerificationTaskIdIn(7L, List.of(41L)).get(0);
        assertEquals(saved.getId(), projection.getId());
        assertThrows(PersistedDataIntegrityException.class,
                () -> new TraceMapper().toSummaryDto(projection, projection));
    }

    @Test
    void availabilityUsesOnePayloadFreeQueryForEmptyUndoRedoAndMixedJournals() {
        BoardEditJournal journal = new BoardEditJournal(journalRepository, new ObjectMapper());
        for (int state = 0; state < 4; state++) {
            long userId = 100L + state;
            if ((state & 1) != 0) journalRepository.save(journalEntry(userId, 1L, false));
            if ((state & 2) != 0) journalRepository.save(journalEntry(userId, 2L, true));
            journalRepository.flush();
            entityManager.clear();
            SqlCapture.STATEMENTS.get().clear();

            assertEquals(new BoardUndoAvailability((state & 1) != 0, (state & 2) != 0), journal.availability(userId));

            var selects = selects();
            assertEquals(1, selects.size());
            assertFalse(selects.get(0).contains("before_json"));
            assertFalse(selects.get(0).contains("after_json"));
        }
    }

    @Test
    void progressQueriesReadOnlyTwoColumnsAndKeepTheOwnershipPredicate() {
        var verification = verificationTasks.saveAndFlush(VerificationTaskPo.builder()
                .userId(7L).status(VerificationTaskPo.TaskStatus.RUNNING).progress(37)
                .createdAt(LocalDateTime.now()).requestJson("{\"frozen\":\"" + "x".repeat(40_000) + "\"}")
                .nusmvOutput("y".repeat(40_000)).build());
        var simulation = simulationTasks.saveAndFlush(SimulationTaskPo.builder()
                .userId(7L).status(SimulationTaskPo.TaskStatus.RUNNING).progress(23)
                .createdAt(LocalDateTime.now()).checkLogsJson("x".repeat(40_000)).build());
        entityManager.clear();
        SqlCapture.STATEMENTS.get().clear();

        assertEquals(37, verificationTasks.findProgressByIdAndUserId(verification.getId(), 7L).orElseThrow().getProgress());
        assertEquals(23, simulationTasks.findProgressByIdAndUserId(simulation.getId(), 7L).orElseThrow().getProgress());
        assertTrue(verificationTasks.findProgressByIdAndUserId(verification.getId(), 8L).isEmpty());
        assertTrue(simulationTasks.findProgressByIdAndUserId(simulation.getId(), 8L).isEmpty());

        var selects = selects();
        assertEquals(4, selects.size());
        for (String sql : selects) {
            assertEquals(2, sql.substring(sql.indexOf("select ") + 7, sql.indexOf(" from ")).split(",").length, sql);
            assertFalse(sql.contains("_json"), sql);
            assertFalse(sql.contains("nusmv_output"), sql);
        }
    }

    @Test
    void orderedHistoryResolvesModelPresenceOutsideTheOuterSortRow() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        String model = "MODULE main\n" + "-- stored model\n".repeat(3000);
        var first = verificationTasks.saveAndFlush(VerificationTaskPo.builder().userId(7L)
                .status(VerificationTaskPo.TaskStatus.COMPLETED).createdAt(now).completedAt(now)
                .smvModelContent("").build());
        var second = verificationTasks.saveAndFlush(VerificationTaskPo.builder().userId(7L)
                .status(VerificationTaskPo.TaskStatus.COMPLETED).createdAt(now).completedAt(now)
                .smvModelContent(model).build());
        verificationTasks.saveAndFlush(VerificationTaskPo.builder().userId(8L)
                .status(VerificationTaskPo.TaskStatus.COMPLETED).createdAt(now).completedAt(now.plusDays(1))
                .smvModelContent(model).build());
        var simulation = simulationTraces.saveAndFlush(SimulationTracePo.builder().userId(7L)
                .statesJson("[]").stateCount(1).createdAt(now).smvModelContent(model).build());
        simulationTraces.saveAndFlush(SimulationTracePo.builder().userId(8L)
                .statesJson("[]").stateCount(1).createdAt(now.plusDays(1)).smvModelContent(model).build());
        entityManager.clear();
        SqlCapture.STATEMENTS.get().clear();

        var page = verificationTasks.findCompletedRunSummaries(7L, VerificationTaskPo.TaskStatus.COMPLETED,
                PageRequest.of(0, 1));
        var next = verificationTasks.findCompletedRunSummaries(7L, VerificationTaskPo.TaskStatus.COMPLETED,
                PageRequest.of(1, 1));
        var simulations = simulationTraces.findSummariesByUserId(7L, PageRequest.of(0, 1));

        assertEquals(List.of(second.getId()), page.stream().map(item -> item.getId()).toList());
        assertTrue(page.get(0).getHasSmvModel());
        assertEquals(List.of(first.getId()), next.stream().map(item -> item.getId()).toList());
        assertFalse(next.get(0).getHasSmvModel());
        assertEquals(List.of(simulation.getId()), simulations.stream().map(item -> item.getId()).toList());
        assertTrue(simulations.get(0).getHasSmvModel());
        assertEquals(3, selects().size());
        for (String sql : selects()) {
            assertTrue(sql.contains("(select case"), sql);
            assertTrue(sql.contains("order by"), sql);
        }
    }

    private List<String> selects() {
        return SqlCapture.STATEMENTS.get().stream().filter(sql -> sql.startsWith("select ")).toList();
    }

    private TracePo trace(Long userId, Long runId, String states, LocalDateTime created) {
        return TracePo.builder().userId(userId).verificationTaskId(runId).violatedSpecId("spec-1")
                .violatedSpecJson("{\"id\":\"spec-1\",\"templateId\":\"1\",\"templateLabel\":\"Always\"}")
                .statesJson(states).stateCount(200).createdAt(created).build();
    }

    private FuzzTaskPo fuzzTask(Long userId, String envelope) {
        return FuzzTaskPo.builder().userId(userId).status(FuzzTaskPo.TaskStatus.COMPLETED)
                .targetSpecIdsJson("[]").maxIterations(1).pathLength(2).populationSize(1)
                .modelInputSnapshotJson(envelope).modelSnapshotJson("{}").build();
    }

    private BoardEditJournalPo journalEntry(Long userId, Long sequence, boolean undone) {
        return BoardEditJournalPo.builder().userId(userId).sequence(sequence).entityType(BoardEditEntityType.RULE)
                .operation(BoardEditOperation.DELETE).entityKey("rule-" + sequence).beforeJson("x".repeat(40_000))
                .undone(undone).createdAt(LocalDateTime.now()).build();
    }
}
