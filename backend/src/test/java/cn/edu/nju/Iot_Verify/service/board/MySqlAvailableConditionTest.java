package cn.edu.nju.Iot_Verify.service.board;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MySqlAvailableConditionTest {
    private final Map<String, String> previousProperties = new HashMap<>();

    @BeforeEach
    void saveProperties() {
        for (String name : new String[]{"iot.verify.mysql.required", "spring.datasource.url",
                "spring.datasource.username", "spring.datasource.password"}) {
            previousProperties.put(name, System.getProperty(name));
        }
    }

    @AfterEach
    void restoreProperties() {
        previousProperties.forEach((name, value) -> {
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
        });
    }

    @Test
    void reachableDatabaseEnablesRequiredTestsAndClosesProbe() throws Exception {
        System.setProperty("iot.verify.mysql.required", "true");
        Connection connection = mock(Connection.class);
        when(connection.isValid(3)).thenReturn(true);
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(anyString(), anyString(), anyString()))
                    .thenReturn(connection);
            assertFalse(new MySqlAvailableCondition().evaluateExecutionCondition(null).isDisabled());
        }
        verify(connection).close();
    }

    @Test
    void unavailableDatabaseIsOptionalForLocalRuns() {
        System.setProperty("iot.verify.mysql.required", "false");
        SQLException unavailable = new SQLException("offline");
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(anyString(), anyString(), anyString()))
                    .thenThrow(unavailable);
            assertTrue(new MySqlAvailableCondition().evaluateExecutionCondition(null).isDisabled());
        }
    }

    @Test
    void unavailableDatabaseFailsRequiredRunInsteadOfSkipping() {
        System.setProperty("iot.verify.mysql.required", "true");
        SQLException unavailable = new SQLException("offline");
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(anyString(), anyString(), anyString()))
                    .thenThrow(unavailable);
            assertThrows(IllegalStateException.class,
                    () -> new MySqlAvailableCondition().evaluateExecutionCondition(null));
        }
    }

    @Test
    void invalidConnectionFailsRequiredRunInsteadOfSkipping() throws Exception {
        System.setProperty("iot.verify.mysql.required", "true");
        Connection connection = mock(Connection.class);
        when(connection.isValid(3)).thenReturn(false);
        try (MockedStatic<DriverManager> driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(anyString(), anyString(), anyString()))
                    .thenReturn(connection);
            assertThrows(IllegalStateException.class,
                    () -> new MySqlAvailableCondition().evaluateExecutionCondition(null));
        }
        verify(connection).close();
    }
}
