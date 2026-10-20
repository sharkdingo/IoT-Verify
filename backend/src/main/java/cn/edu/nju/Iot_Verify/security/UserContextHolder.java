package cn.edu.nju.Iot_Verify.security;

public final class UserContextHolder {

    private static final ThreadLocal<Long> currentUserId = new ThreadLocal<>();
    private static final ThreadLocal<String> currentChatSessionId = new ThreadLocal<>();
    private static final ThreadLocal<String> currentChatExecutionId = new ThreadLocal<>();
    private static final ThreadLocal<String> currentChatUserMessage = new ThreadLocal<>();
    private static final ThreadLocal<BoardWritePermission> currentBoardWritePermission = new ThreadLocal<>();
    private static final ThreadLocal<String> confirmedProtectedActionKind = new ThreadLocal<>();
    private static final ThreadLocal<String> confirmedProtectedActionId = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void setUserId(Long userId) {
        currentUserId.set(userId);
    }

    public static Long getUserId() {
        return currentUserId.get();
    }

    public static void setChatSessionId(String sessionId) {
        currentChatSessionId.set(sessionId);
    }

    public static String getChatSessionId() {
        return currentChatSessionId.get();
    }

    public static void setChatExecutionId(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            currentChatExecutionId.remove();
            return;
        }
        currentChatExecutionId.set(executionId);
    }

    public static String getChatExecutionId() {
        return currentChatExecutionId.get();
    }

    public static void setChatUserMessage(String message) {
        currentChatUserMessage.set(message);
        currentBoardWritePermission.remove();
    }

    public static String getChatUserMessage() { return currentChatUserMessage.get(); }

    public static void setBoardWritePermission(BoardWritePermission permission) {
        currentBoardWritePermission.set(permission);
    }

    public static BoardWritePermission getBoardWritePermission() { return currentBoardWritePermission.get(); }

    public enum BoardWritePermission { ALLOWED, DENIED, UNAVAILABLE }

    public static boolean isDestructiveActionConfirmed() {
        return isProtectedActionConfirmed("DESTRUCTIVE");
    }

    public static boolean isSceneReplacementConfirmed() {
        return isProtectedActionConfirmed("SCENE_REPLACEMENT");
    }

    public static boolean isDefaultTemplateResetConfirmed() {
        return isProtectedActionConfirmed("DEFAULT_TEMPLATE_RESET");
    }

    public static void setConfirmedProtectedAction(String kind, String confirmationId) {
        if (kind == null || kind.isBlank() || confirmationId == null || confirmationId.isBlank()) {
            confirmedProtectedActionKind.remove();
            confirmedProtectedActionId.remove();
            return;
        }
        confirmedProtectedActionKind.set(kind.trim());
        confirmedProtectedActionId.set(confirmationId);
    }

    public static String getConfirmedProtectedActionId() {
        return confirmedProtectedActionId.get();
    }

    public static boolean isProtectedActionConfirmed(String kind) {
        return kind != null && kind.equals(confirmedProtectedActionKind.get());
    }

    public static void clear() {
        currentUserId.remove();
        currentChatSessionId.remove();
        currentChatExecutionId.remove();
        currentChatUserMessage.remove();
        currentBoardWritePermission.remove();
        confirmedProtectedActionKind.remove();
        confirmedProtectedActionId.remove();
    }
}
