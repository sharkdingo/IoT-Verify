package cn.edu.nju.Iot_Verify.configure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSchemaConstraintsTest {

    /** Tables this fixture seeds one valid row into. */
    private static final List<String> GENERIC_USER_TABLES = List.of(
            "trace",
            "verification_task",
            "simulation_task",
            "simulation_trace",
            "device_node",
            "board_environment_variable",
            "rules",
            "specification",
            "board_layout",
            "device_templates",
            "board_edit_journal");

    /** Tables the fixture seeds by hand because their rows need specific shapes. */
    private static final List<String> HAND_SEEDED_USER_TABLES = List.of(
            "fuzz_finding",
            "fuzz_task",
            "chat_session",
            "ai_session_state");

    @Test
    void everyProductionConstraintTableIsCoveredByThisFixture() throws Exception {
        java.lang.reflect.Field field = DatabaseSchemaConstraints.class.getDeclaredField("FOREIGN_KEYS");
        field.setAccessible(true);
        List<?> constraints = (List<?>) field.get(null);
        java.util.Set<String> production = new java.util.HashSet<>();
        for (Object constraint : constraints) {
            java.lang.reflect.Method table = constraint.getClass().getDeclaredMethod("table");
            table.setAccessible(true);
            production.add((String) table.invoke(constraint));
        }
        List<String> covered = new ArrayList<>(GENERIC_USER_TABLES);
        covered.addAll(HAND_SEEDED_USER_TABLES);
        covered.add("chat_message");
        covered.add("chat_session_pre_admission_stop");
        assertEquals(java.util.Set.copyOf(covered), production);
    }

    @Test
    void configure_enforcesCascadeOwnershipWithoutRewritingValidRows() throws Exception {
        DataSource dataSource = dataSource();
        createSchemaAndRows(dataSource);
        DatabaseSchemaConstraints constraints = new DatabaseSchemaConstraints(dataSource);

        constraints.configure();
        constraints.configure();

        assertEquals(1, count(dataSource, "chat_session_pre_admission_stop"));
        assertEquals(1, count(dataSource, "chat_message"));
        assertEquals(1, count(dataSource, "chat_session"));
        assertEquals(1, count(dataSource, "ai_session_state"));
        assertEquals(1, count(dataSource, "fuzz_finding"));
        assertEquals(1, count(dataSource, "fuzz_task"));
        for (String table : GENERIC_USER_TABLES) {
            assertEquals(1, count(dataSource, table), table);
        }
        assertCascadeForeignKeys(dataSource);

        insertSecondUserGraph(dataSource);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM app_user WHERE id = 2");
        }

        assertEquals(1, count(dataSource, "chat_session_pre_admission_stop"));
        assertEquals(1, count(dataSource, "chat_message"));
        assertEquals(1, count(dataSource, "chat_session"));
        assertEquals(1, count(dataSource, "ai_session_state"));
        assertEquals(1, count(dataSource, "fuzz_finding"));
        assertEquals(1, count(dataSource, "fuzz_task"));
        for (String table : GENERIC_USER_TABLES) {
            assertEquals(1, count(dataSource, table), table);
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class,
                    () -> statement.executeUpdate("INSERT INTO rules (id, user_id) VALUES (99, 99)"));
            assertThrows(SQLException.class,
                    () -> statement.executeUpdate(
                            "INSERT INTO chat_session_pre_admission_stop (session_id, turn_id) "
                                    + "VALUES ('missing', 'turn-missing')"));
            assertThrows(SQLException.class,
                    () -> statement.executeUpdate(
                            "INSERT INTO chat_message (id, session_id) VALUES (99, 'missing')"));
            assertThrows(SQLException.class,
                    () -> statement.executeUpdate(
                            "INSERT INTO ai_session_state (state_key, user_id, session_id) "
                                    + "VALUES ('missing-session', 1, 'missing')"));
            assertThrows(SQLException.class,
                    () -> statement.executeUpdate(
                            "INSERT INTO fuzz_finding (id, user_id, fuzz_task_id) VALUES (99, 1, 999)"));
            statement.executeUpdate("INSERT INTO app_user (id) VALUES (3)");
            statement.executeUpdate("INSERT INTO chat_session (id, user_id) VALUES ('owner-3', 3)");
            assertThrows(SQLException.class,
                    () -> statement.executeUpdate(
                            "INSERT INTO ai_session_state (state_key, user_id, session_id) "
                                    + "VALUES ('wrong-owner', 1, 'owner-3')"));
            statement.executeUpdate("INSERT INTO fuzz_task (id, user_id) VALUES (30, 3)");
            assertThrows(SQLException.class,
                    () -> statement.executeUpdate(
                            "INSERT INTO fuzz_finding (id, user_id, fuzz_task_id) VALUES (100, 1, 30)"));
        }
    }

    @Test
    void configure_rejectsInvalidOwnershipWithoutDeletingEvidence() throws Exception {
        DataSource source = dataSource();
        createSchemaAndRows(source);
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO rules (id, user_id) VALUES (99, 99)");
        }
        assertThrows(IllegalStateException.class, () -> new DatabaseSchemaConstraints(source).configure());
        assertEquals(2, count(source, "rules"));
    }

    private void createSchemaAndRows(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE app_user (id BIGINT PRIMARY KEY)");
            statement.executeUpdate("CREATE TABLE chat_session (id VARCHAR(100) PRIMARY KEY, user_id BIGINT NOT NULL, CONSTRAINT uk_chat_session_user_id UNIQUE (user_id, id))");
            statement.executeUpdate("CREATE TABLE chat_session_pre_admission_stop ("
                    + "session_id VARCHAR(100) NOT NULL, turn_id VARCHAR(64) NOT NULL, "
                    + "PRIMARY KEY (session_id, turn_id))");
            statement.executeUpdate("CREATE TABLE chat_message (id BIGINT PRIMARY KEY, session_id VARCHAR(100) NOT NULL)");
            statement.executeUpdate("CREATE TABLE ai_session_state ("
                    + "state_key VARCHAR(200) PRIMARY KEY, user_id BIGINT NOT NULL, session_id VARCHAR(100) NOT NULL)");
            statement.executeUpdate("CREATE TABLE fuzz_task (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, CONSTRAINT uk_fuzz_task_user_id UNIQUE (user_id, id))");
            statement.executeUpdate("CREATE TABLE fuzz_finding ("
                    + "id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, fuzz_task_id BIGINT NOT NULL)");
            for (String table : GENERIC_USER_TABLES) {
                statement.executeUpdate("CREATE TABLE `" + table + "` (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL)");
            }

            statement.executeUpdate("INSERT INTO app_user (id) VALUES (1)");
            statement.executeUpdate("INSERT INTO chat_session (id, user_id) VALUES ('valid', 1)");
            statement.executeUpdate("INSERT INTO chat_session_pre_admission_stop (session_id, turn_id) VALUES "
                    + "('valid', 'turn-valid')");
            statement.executeUpdate("INSERT INTO chat_message (id, session_id) VALUES "
                    + "(1, 'valid')");
            statement.executeUpdate("INSERT INTO ai_session_state (state_key, user_id, session_id) VALUES "
                    + "('valid-state', 1, 'valid')");
            statement.executeUpdate("INSERT INTO fuzz_task (id, user_id) VALUES (1, 1)");
            statement.executeUpdate("INSERT INTO fuzz_finding (id, user_id, fuzz_task_id) VALUES "
                    + "(1, 1, 1)");
            for (String table : GENERIC_USER_TABLES) {
                statement.executeUpdate("INSERT INTO `" + table + "` (id, user_id) VALUES (1, 1)");
            }
        }
    }

    private void insertSecondUserGraph(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO app_user (id) VALUES (2)");
            statement.executeUpdate("INSERT INTO chat_session (id, user_id) VALUES ('cascade', 2)");
            statement.executeUpdate("INSERT INTO chat_session_pre_admission_stop (session_id, turn_id) "
                    + "VALUES ('cascade', 'turn-cascade')");
            statement.executeUpdate("INSERT INTO chat_message (id, session_id) VALUES (20, 'cascade')");
            statement.executeUpdate("INSERT INTO ai_session_state (state_key, user_id, session_id) "
                    + "VALUES ('cascade-state', 2, 'cascade')");
            statement.executeUpdate("INSERT INTO fuzz_task (id, user_id) VALUES (20, 2)");
            statement.executeUpdate("INSERT INTO fuzz_finding (id, user_id, fuzz_task_id) VALUES (20, 2, 20)");
            for (String table : GENERIC_USER_TABLES) {
                statement.executeUpdate("INSERT INTO `" + table + "` (id, user_id) VALUES (20, 2)");
            }
        }
    }

    private void assertCascadeForeignKeys(DataSource dataSource) throws Exception {
        assertCascadeForeignKey(dataSource, "chat_session_pre_admission_stop",
                "fk_chat_pre_admission_stop_session",
                List.of("session_id"), "chat_session", List.of("id"));
        assertCascadeForeignKey(dataSource, "chat_message", "fk_chat_message_session",
                List.of("session_id"), "chat_session", List.of("id"));
        assertCascadeForeignKey(dataSource, "ai_session_state", "fk_ai_session_state_session_owner",
                List.of("user_id", "session_id"), "chat_session", List.of("user_id", "id"));
        assertCascadeForeignKey(dataSource, "fuzz_finding", "fk_fuzz_finding_task_owner",
                List.of("user_id", "fuzz_task_id"), "fuzz_task", List.of("user_id", "id"));
        assertCascadeForeignKey(dataSource, "chat_session", "fk_chat_session_user",
                List.of("user_id"), "app_user", List.of("id"));
        assertCascadeForeignKey(dataSource, "fuzz_finding", "fk_fuzz_finding_user",
                List.of("user_id"), "app_user", List.of("id"));
        assertCascadeForeignKey(dataSource, "fuzz_task", "fk_fuzz_task_user",
                List.of("user_id"), "app_user", List.of("id"));
        for (String table : GENERIC_USER_TABLES) {
            assertCascadeForeignKey(dataSource, table, null,
                    List.of("user_id"), "app_user", List.of("id"));
        }
    }

    private void assertCascadeForeignKey(
            DataSource dataSource,
            String table,
            String expectedName,
            List<String> childColumns,
            String parentTable,
            List<String> parentColumns) throws Exception {
        Map<String, List<ImportedColumn>> byName = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet keys = connection.getMetaData().getImportedKeys(connection.getCatalog(), null, table)) {
            while (keys.next()) {
                String name = keys.getString("FK_NAME");
                byName.computeIfAbsent(name, ignored -> new ArrayList<>()).add(new ImportedColumn(
                        keys.getShort("KEY_SEQ"),
                        keys.getString("FKCOLUMN_NAME"),
                        keys.getString("PKTABLE_NAME"),
                        keys.getString("PKCOLUMN_NAME"),
                        keys.getShort("DELETE_RULE")));
            }
        }
        for (Map.Entry<String, List<ImportedColumn>> entry : byName.entrySet()) {
            if (expectedName != null && !expectedName.equalsIgnoreCase(entry.getKey())) continue;
            List<ImportedColumn> columns = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(ImportedColumn::sequence))
                    .toList();
            if (!sameColumns(childColumns, columns.stream().map(ImportedColumn::childColumn).toList())) continue;
            assertTrue(columns.stream().allMatch(column ->
                    parentTable.equalsIgnoreCase(column.parentTable())));
            assertTrue(sameColumns(parentColumns, columns.stream().map(ImportedColumn::parentColumn).toList()));
            assertTrue(columns.stream().allMatch(column ->
                    column.deleteRule() == DatabaseMetaData.importedKeyCascade));
            return;
        }
        throw new AssertionError("Missing cascade foreign key for " + table + childColumns);
    }

    private boolean sameColumns(List<String> expected, List<String> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equalsIgnoreCase(actual.get(i))) return false;
        }
        return true;
    }

    private DataSource dataSource() {
        String name = "schema_constraints_" + UUID.randomUUID().toString().replace("-", "");
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    private int count(DataSource dataSource, String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private record ImportedColumn(
            short sequence,
            String childColumn,
            String parentTable,
            String parentColumn,
            short deleteRule) {
    }
}
