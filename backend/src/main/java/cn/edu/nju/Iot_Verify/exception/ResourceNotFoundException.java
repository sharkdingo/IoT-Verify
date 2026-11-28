package cn.edu.nju.Iot_Verify.exception;

public class ResourceNotFoundException extends BaseException {

    public ResourceNotFoundException(String message) {
        super(404, message);
    }

    public ResourceNotFoundException(String resourceType, String identifier) {
        super(404, String.format("%s not found: %s", resourceType, identifier));
    }

    public ResourceNotFoundException(String resourceType, Long id) {
        super(404, String.format("%s not found with id: %d", resourceType, id));
    }

    public static ResourceNotFoundException user(Long id) {
        return new ResourceNotFoundException("User", id);
    }

    public static ResourceNotFoundException session(String sessionId) {
        return new ResourceNotFoundException("Chat session", sessionId);
    }
}
