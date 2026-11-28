package cn.edu.nju.Iot_Verify.exception;

public class ServiceUnavailableException extends BaseException {

    public ServiceUnavailableException(String message) {
        super(503, message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(503, message, cause);
    }

    public static ServiceUnavailableException aiService() {
        return new ServiceUnavailableException("AI service is temporarily unavailable");
    }

    public static ServiceUnavailableException aiService(Throwable cause) {
        return new ServiceUnavailableException("AI service is temporarily unavailable", cause);
    }
}
