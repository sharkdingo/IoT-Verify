package cn.edu.nju.Iot_Verify.testutil;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;

public final class SqlStatementCapture implements StatementInspector {
    private static final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);

    @Override
    public String inspect(String sql) {
        statements.get().add(sql);
        return sql;
    }

    public static List<String> take() {
        List<String> captured = List.copyOf(statements.get());
        statements.remove();
        return captured;
    }
}
