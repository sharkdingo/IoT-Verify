package cn.edu.nju.Iot_Verify.service.recommendation;

import cn.edu.nju.Iot_Verify.exception.BadGatewayException;

import java.util.Map;

/** An unusable provider result, with the diagnostic fields preserved at the tool boundary. */
public class RecommendationFailure extends BadGatewayException {
    private final String errorCode;
    private final Map<String, Object> details;

    public RecommendationFailure(String message, String errorCode,
                                 Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = Map.copyOf(details);
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
