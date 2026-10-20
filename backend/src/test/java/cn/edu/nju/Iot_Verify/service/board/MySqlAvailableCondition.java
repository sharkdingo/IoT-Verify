package cn.edu.nju.Iot_Verify.service.board;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Enables a test class only when a MySQL server is actually reachable.
 *
 * <p>Some undo behaviour depends on MySQL parsing a string bound to a {@code JSON} column, which H2
 * cannot emulate. Rather than assert it against a database that answers differently — or leave the
 * cases permanently {@code @Disabled} — the class runs where MySQL exists and reports as skipped
 * where it does not. CI provisions a dedicated test database and requires these tests with
 * {@code -Diot.verify.mysql.required=true}; an unavailable database then fails the run instead of
 * silently removing persistence and concurrency coverage.
 *
 * <p>The connection details come from the same environment variables the application uses, so a
 * developer who can run the app can run these tests. It also *sets* the datasource properties, so the
 * test class does not have to hardcode a URL.
 */
public class MySqlAvailableCondition implements ExecutionCondition {

    public static final String URL = System.getenv().getOrDefault("IOT_VERIFY_UNDO_IT_URL",
            "jdbc:mysql://localhost:3306/iot_verify_undo_it?useSSL=false&serverTimezone=UTC"
                    + "&characterEncoding=utf-8&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true");
    public static final String USERNAME = System.getenv().getOrDefault("DB_USERNAME", "root");
    public static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        // Spring reads these when the context starts; setting them here keeps the URL in one place.
        System.setProperty("spring.datasource.url", URL);
        System.setProperty("spring.datasource.username", USERNAME);
        System.setProperty("spring.datasource.password", PASSWORD);
        try (Connection connection = DriverManager.getConnection(URL, USERNAME, PASSWORD)) {
            return connection.isValid(3)
                    ? ConditionEvaluationResult.enabled("MySQL is reachable")
                    : unavailable("MySQL connection is not valid");
        } catch (SQLException e) {
            return unavailable("MySQL is not reachable (" + e.getClass().getSimpleName() + ")");
        }
    }

    private ConditionEvaluationResult unavailable(String reason) {
        if (Boolean.getBoolean("iot.verify.mysql.required")) {
            throw new IllegalStateException(reason + "; MySQL integration tests are required");
        }
        return ConditionEvaluationResult.disabled(reason + "; skipping MySQL integration tests");
    }
}
