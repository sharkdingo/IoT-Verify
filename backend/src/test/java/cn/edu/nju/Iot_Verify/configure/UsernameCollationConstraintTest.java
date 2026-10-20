package cn.edu.nju.Iot_Verify.configure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsernameCollationConstraintTest {

    @Test
    void configure_isNoOpOutsideMySql() {
        String name = "username_collation_" + UUID.randomUUID().toString().replace("-", "");
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");

        assertFalse(new DatabaseSchemaConstraints(dataSource).ensureUsernameCollation());
    }

    @Test
    void configure_setsCaseAndAccentSensitiveCollationAndVerifiesResult() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection initialInspection = inspectionConnection("utf8mb4_unicode_ci");
        Connection migrationConnection = mock(Connection.class);
        Statement migrationStatement = mock(Statement.class);
        when(migrationConnection.createStatement()).thenReturn(migrationStatement);
        Connection finalInspection = inspectionConnection(DatabaseSchemaConstraints.TARGET_COLLATION);
        when(dataSource.getConnection()).thenReturn(
                initialInspection, migrationConnection, finalInspection);

        assertTrue(new DatabaseSchemaConstraints(dataSource).ensureUsernameCollation());

        verify(migrationStatement).executeUpdate(contains(DatabaseSchemaConstraints.TARGET_COLLATION));
    }

    @Test
    void configure_isNoOpWhenMySqlColumnAlreadyUsesTargetCollation() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection inspection = inspectionConnection(DatabaseSchemaConstraints.TARGET_COLLATION);
        when(dataSource.getConnection()).thenReturn(inspection);

        assertFalse(new DatabaseSchemaConstraints(dataSource).ensureUsernameCollation());
    }

    @Test
    void configure_failsClosedWhenMySqlUsernameColumnHasNoCollation() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection inspection = inspectionConnection(null);
        when(dataSource.getConnection()).thenReturn(inspection);

        assertThrows(IllegalStateException.class,
                () -> new DatabaseSchemaConstraints(dataSource).ensureUsernameCollation());
    }

    @Test
    void configure_acceptsAnotherInstanceConfiguringTheSameCollation() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection initialInspection = inspectionConnection("utf8mb4_unicode_ci");
        Connection migrationConnection = mock(Connection.class);
        Statement migrationStatement = mock(Statement.class);
        when(migrationConnection.createStatement()).thenReturn(migrationStatement);
        when(migrationStatement.executeUpdate(contains(DatabaseSchemaConstraints.TARGET_COLLATION)))
                .thenThrow(new SQLException("concurrent alter"));
        Connection concurrentResult = inspectionConnection(DatabaseSchemaConstraints.TARGET_COLLATION);
        when(dataSource.getConnection()).thenReturn(
                initialInspection, migrationConnection, concurrentResult);

        assertTrue(new DatabaseSchemaConstraints(dataSource).ensureUsernameCollation());
    }

    @Test
    void configure_failsWhenAlterFailsAndTargetCollationWasNotReached() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection initialInspection = inspectionConnection("utf8mb4_unicode_ci");
        Connection migrationConnection = mock(Connection.class);
        Statement migrationStatement = mock(Statement.class);
        when(migrationConnection.createStatement()).thenReturn(migrationStatement);
        when(migrationStatement.executeUpdate(contains(DatabaseSchemaConstraints.TARGET_COLLATION)))
                .thenThrow(new SQLException("alter failed"));
        Connection failedResult = inspectionConnection("utf8mb4_unicode_ci");
        when(dataSource.getConnection()).thenReturn(
                initialInspection, migrationConnection, failedResult);

        assertThrows(IllegalStateException.class,
                () -> new DatabaseSchemaConstraints(dataSource).ensureUsernameCollation());
    }

    private Connection inspectionConnection(String collation) throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(connection.prepareStatement(contains("information_schema.COLUMNS"))).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn(collation);
        return connection;
    }
}
