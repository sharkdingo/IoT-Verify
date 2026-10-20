package cn.edu.nju.Iot_Verify.configure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Completes the current schema's constraints after Hibernate creates its scalar-id columns.
 * Keeping foreign keys here avoids adding entity associations solely to generate DDL.
 */
@Slf4j
@Component
public class DatabaseSchemaConstraints implements SmartInitializingSingleton {

    static final String TARGET_COLLATION = "utf8mb4_0900_as_cs";
    private static final String COLLATION_QUERY = "SELECT COLLATION_NAME "
            + "FROM information_schema.COLUMNS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user' AND COLUMN_NAME = 'username'";
    private static final String USERNAME_COLLATION_SQL = "ALTER TABLE `app_user` MODIFY `username` "
            + "VARCHAR(50) CHARACTER SET utf8mb4 COLLATE " + TARGET_COLLATION + " NOT NULL";

    private static final List<ForeignKeySpec> FOREIGN_KEYS = List.of(
            userForeignKey("trace", "fk_trace_user"),
            userForeignKey("verification_task", "fk_verification_task_user"),
            userForeignKey("simulation_task", "fk_simulation_task_user"),
            userForeignKey("simulation_trace", "fk_simulation_trace_user"),
            userForeignKey("fuzz_finding", "fk_fuzz_finding_user"),
            userForeignKey("fuzz_task", "fk_fuzz_task_user"),
            userForeignKey("chat_session", "fk_chat_session_user"),
            userForeignKey("device_node", "fk_device_node_user"),
            userForeignKey("board_environment_variable", "fk_board_environment_user"),
            userForeignKey("rules", "fk_rules_user"),
            userForeignKey("specification", "fk_specification_user"),
            userForeignKey("board_layout", "fk_board_layout_user"),
            userForeignKey("device_templates", "fk_device_templates_user"),
            userForeignKey("board_edit_journal", "fk_board_edit_journal_user"),
            new ForeignKeySpec(
                    "chat_message", List.of("session_id"),
                    "chat_session", List.of("id"), "fk_chat_message_session"),
            new ForeignKeySpec(
                    "chat_session_pre_admission_stop", List.of("session_id"),
                    "chat_session", List.of("id"), "fk_chat_pre_admission_stop_session"),
            new ForeignKeySpec(
                    "ai_session_state", List.of("user_id", "session_id"),
                    "chat_session", List.of("user_id", "id"), "fk_ai_session_state_session_owner"),
            new ForeignKeySpec(
                    "fuzz_finding", List.of("user_id", "fuzz_task_id"),
                    "fuzz_task", List.of("user_id", "id"), "fk_fuzz_finding_task_owner"));

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaConstraints(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public void afterSingletonsInstantiated() {
        configure();
    }

    void configure() {
        ensureUsernameCollation();
        FOREIGN_KEYS.forEach(this::ensureCascadeForeignKey);
    }

    private void ensureCascadeForeignKey(ForeignKeySpec spec) {
        ForeignKeyState state = readForeignKeyState(spec);
        if (state == ForeignKeyState.VALID) return;
        if (state == ForeignKeyState.INVALID) {
            throw new IllegalStateException("Unsafe foreign key on " + spec.table() + spec.columns()
                    + "; expected ON DELETE CASCADE to " + spec.parentTable() + spec.parentColumns());
        }
        String childColumns = quotedColumns(spec.columns());
        String parentColumns = quotedColumns(spec.parentColumns());
        try {
            jdbcTemplate.execute("ALTER TABLE `" + spec.table() + "` ADD CONSTRAINT `" + spec.name()
                    + "` FOREIGN KEY (" + childColumns + ") REFERENCES `" + spec.parentTable()
                    + "` (" + parentColumns + ") ON DELETE CASCADE");
        } catch (DataAccessException e) {
            if (readForeignKeyState(spec) == ForeignKeyState.VALID) return;
            throw new IllegalStateException("Could not create cascade foreign key " + spec.name(), e);
        }
    }

    private ForeignKeyState readForeignKeyState(ForeignKeySpec spec) {
        Map<String, List<ForeignKeyColumn>> byName = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet keys = connection.getMetaData().getImportedKeys(
                     connection.getCatalog(), null, spec.table())) {
            while (keys.next()) {
                String name = keys.getString("FK_NAME");
                if (name == null) name = "unnamed-" + byName.size();
                byName.computeIfAbsent(name, ignored -> new ArrayList<>()).add(new ForeignKeyColumn(
                        keys.getShort("KEY_SEQ"),
                        keys.getString("FKCOLUMN_NAME"),
                        keys.getString("PKTABLE_NAME"),
                        keys.getString("PKCOLUMN_NAME"),
                        keys.getShort("DELETE_RULE")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not inspect foreign keys for " + spec.table(), e);
        }

        boolean invalidExactShape = false;
        for (List<ForeignKeyColumn> columns : byName.values()) {
            List<ForeignKeyColumn> ordered = columns.stream()
                    .sorted(Comparator.comparingInt(ForeignKeyColumn::sequence))
                    .toList();
            List<String> childColumns = ordered.stream().map(ForeignKeyColumn::childColumn).toList();
            if (!sameColumns(spec.columns(), childColumns)) continue;
            boolean parentMatches = ordered.stream().allMatch(column ->
                    spec.parentTable().equalsIgnoreCase(column.parentTable()))
                    && sameColumns(spec.parentColumns(), ordered.stream()
                            .map(ForeignKeyColumn::parentColumn).toList());
            boolean cascades = ordered.stream().allMatch(column ->
                    column.deleteRule() == DatabaseMetaData.importedKeyCascade);
            if (parentMatches && cascades) return ForeignKeyState.VALID;
            invalidExactShape = true;
        }
        return invalidExactShape ? ForeignKeyState.INVALID : ForeignKeyState.MISSING;
    }

    private boolean sameColumns(List<String> expected, List<String> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equalsIgnoreCase(actual.get(i))) return false;
        }
        return true;
    }

    private String quotedColumns(List<String> columns) {
        return columns.stream().map(column -> "`" + column + "`")
                .reduce((left, right) -> left + ", " + right).orElseThrow();
    }

    private static ForeignKeySpec userForeignKey(String table, String name) {
        return new ForeignKeySpec(table, List.of("user_id"), "app_user", List.of("id"), name);
    }

    private enum ForeignKeyState {
        MISSING,
        VALID,
        INVALID
    }

    private record ForeignKeySpec(
            String table,
            List<String> columns,
            String parentTable,
            List<String> parentColumns,
            String name) {
    }

    private record ForeignKeyColumn(
            short sequence,
            String childColumn,
            String parentTable,
            String parentColumn,
            short deleteRule) {
    }

    boolean ensureUsernameCollation() {
        CollationInspection initial = inspectCollation();
        if (!initial.mySql()) return false;
        String current = initial.collation();
        if (TARGET_COLLATION.equalsIgnoreCase(current)) return false;

        log.warn("Setting app_user.username collation from {} to {}", current, TARGET_COLLATION);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(USERNAME_COLLATION_SQL);
        } catch (SQLException configurationFailure) {
            CollationInspection afterConcurrentAttempt = inspectCollation();
            if (afterConcurrentAttempt.mySql()
                    && TARGET_COLLATION.equalsIgnoreCase(afterConcurrentAttempt.collation())) {
                log.info("Username collation was configured by another application instance");
                return true;
            }
            throw new IllegalStateException("Could not configure app_user.username collation from "
                    + current + " to " + TARGET_COLLATION, configurationFailure);
        }

        CollationInspection migrated = inspectCollation();
        if (!migrated.mySql() || !TARGET_COLLATION.equalsIgnoreCase(migrated.collation())) {
            throw new IllegalStateException("Username collation configuration completed without reaching "
                    + TARGET_COLLATION + "; current collation is " + migrated.collation());
        }
        log.info("Username collation configured");
        return true;
    }

    private CollationInspection inspectCollation() {
        try (Connection connection = dataSource.getConnection()) {
            if (!"MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                return new CollationInspection(false, null);
            }
            try (PreparedStatement statement = connection.prepareStatement(COLLATION_QUERY);
                 ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("Could not find app_user.username in the active MySQL schema");
                }
                String collation = resultSet.getString(1);
                if (collation == null || collation.isBlank()) {
                    throw new IllegalStateException(
                            "app_user.username has no verifiable character collation in the active MySQL schema");
                }
                return new CollationInspection(true, collation);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not inspect app_user.username collation", e);
        }
    }

    private record CollationInspection(boolean mySql, String collation) {
    }
}
