package cn.edu.nju.Iot_Verify.po;

import java.util.List;

/** Result presentation fields shared by the entity and the bounded database read. */
public interface VerificationResultView extends VerificationTaskView {
    String getSpecResultsJson();
    String getCheckLogsJson();
    String getNusmvOutput();
    Boolean getHasSmvModel();

    default List<String> getCheckLogs() {
        return null;
    }
}
