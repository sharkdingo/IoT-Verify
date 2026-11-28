package cn.edu.nju.Iot_Verify.exception;

/**
 * Exception thrown when SMV model generation fails.
 */
public class SmvGenerationException extends InternalServerException {

    private final String errorCategory;

    private SmvGenerationException(String message, String errorCategory) {
        super(message);
        this.errorCategory = errorCategory;
    }

    private SmvGenerationException(String message, String errorCategory, Throwable cause) {
        super(message, cause);
        this.errorCategory = errorCategory;
    }

    public String getErrorCategory() {
        return errorCategory;
    }

    // ==================== Static Factory Methods ====================

    public static SmvGenerationException templateInvalid(String deviceId, String reason) {
        return new SmvGenerationException(
                "Invalid template for device [" + deviceId + "]: " + reason,
                ErrorCategories.TEMPLATE_INVALID
        );
    }

    public static SmvGenerationException manifestParseError(String templateName, Throwable cause) {
        return new SmvGenerationException(
                "Failed to parse manifest for template [" + templateName + "]: " + cause.getMessage(),
                ErrorCategories.MANIFEST_PARSE_ERROR,
                cause
        );
    }

    public static SmvGenerationException deviceNotFound(String deviceId) {
        return new SmvGenerationException(
                "Device not found in SMV map: " + deviceId,
                ErrorCategories.DEVICE_NOT_FOUND
        );
    }

    public static SmvGenerationException specificationError(String specId, String reason) {
        return new SmvGenerationException(
                "Invalid specification [" + specId + "]: " + reason,
                ErrorCategories.SPECIFICATION_ERROR
        );
    }

    public static SmvGenerationException smvGenerationError(String reason) {
        return new SmvGenerationException(
                "SMV generation failed: " + reason,
                ErrorCategories.SMV_GENERATION_ERROR
        );
    }

    public static SmvGenerationException multipleDevicesFailed(String failedDevices) {
        return new SmvGenerationException(
                "Cannot generate SMV model: templates not found for " + failedDevices,
                ErrorCategories.MULTIPLE_DEVICES_FAILED
        );
    }

    public static SmvGenerationException invalidBuilderInput(String builderName,
                                                             String parameterName,
                                                             String reason) {
        return new SmvGenerationException(
                String.format("%s received invalid input '%s': %s",
                        builderName, parameterName, reason),
                ErrorCategories.INVALID_BUILDER_INPUT
        );
    }

    public static SmvGenerationException templateLoadError(String templateName, Throwable cause) {
        return new SmvGenerationException(
                "Failed to load template [" + templateName + "]: " + cause.getMessage(),
                ErrorCategories.TEMPLATE_LOAD_ERROR,
                cause
        );
    }

    // ==================== 模型校验 (P1-P5) ====================

    public static SmvGenerationException illegalTriggerAttribute(String device, String transitionOrApi, String attr, Object legalAttrs) {
        return new SmvGenerationException(
                String.format("Device '%s': '%s' has illegal trigger attribute '%s'. Legal attributes: %s",
                        device, transitionOrApi, attr, legalAttrs),
                ErrorCategories.ILLEGAL_TRIGGER_ATTRIBUTE
        );
    }

    public static SmvGenerationException illegalTriggerRelation(String device, String transitionOrApi,
                                                                String relation, Object legalRelations) {
        return new SmvGenerationException(
                String.format("Device '%s': '%s' has illegal trigger relation '%s'. Legal relations: %s",
                        device, transitionOrApi, relation, legalRelations),
                ErrorCategories.ILLEGAL_TRIGGER_RELATION
        );
    }

    public static SmvGenerationException invalidStateFormat(String device, String itemType, String itemName,
                                                            String stateStr, String reason) {
        return new SmvGenerationException(
                String.format("Device '%s': %s '%s' has invalid StartState/EndState '%s': %s",
                        device, itemType, itemName, stateStr, reason),
                ErrorCategories.INVALID_STATE_FORMAT
        );
    }

    public static SmvGenerationException envVarConflict(String varName, String reason) {
        return new SmvGenerationException(
                String.format("Env variable '%s' conflict: %s", varName, reason),
                ErrorCategories.ENV_VAR_CONFLICT
        );
    }

    public static SmvGenerationException ambiguousDeviceReference(String requestedName, Object candidates) {
        return new SmvGenerationException(
                String.format("Ambiguous device reference '%s': candidates %s",
                        requestedName, candidates),
                ErrorCategories.AMBIGUOUS_DEVICE_REFERENCE
        );
    }

    public static SmvGenerationException trustPrivacyConflict(String device, String key, String prev, String current) {
        return new SmvGenerationException(
                String.format("Device '%s': trust/privacy conflict for '%s': '%s' vs '%s'",
                        device, key, prev, current),
                ErrorCategories.TRUST_PRIVACY_CONFLICT
        );
    }

    public static SmvGenerationException incompleteTrigger(String device, String transitionOrApi, String reason) {
        return new SmvGenerationException(
                String.format("Device '%s': '%s' has incomplete trigger: %s",
                        device, transitionOrApi, reason),
                ErrorCategories.INCOMPLETE_TRIGGER
        );
    }

    // ==================== 规约校验 ====================

    public static SmvGenerationException privacySpecWithoutPrivacyEnabled(String specId) {
        return new SmvGenerationException(
                "Specification [" + specId + "] references privacy properties, "
                + "but privacy mode is not enabled. "
                + "Enable privacy mode or remove privacy conditions from the specification.",
                ErrorCategories.PRIVACY_SPEC_WITHOUT_PRIVACY
        );
    }

    // ==================== Error Categories ====================

    public static class ErrorCategories {
        public static final String TEMPLATE_INVALID = "TEMPLATE_INVALID";
        public static final String MANIFEST_PARSE_ERROR = "MANIFEST_PARSE_ERROR";
        public static final String DEVICE_NOT_FOUND = "DEVICE_NOT_FOUND";
        public static final String SPECIFICATION_ERROR = "SPECIFICATION_ERROR";
        public static final String SMV_GENERATION_ERROR = "SMV_GENERATION_ERROR";
        public static final String MULTIPLE_DEVICES_FAILED = "MULTIPLE_DEVICES_FAILED";
        public static final String ILLEGAL_TRIGGER_ATTRIBUTE = "ILLEGAL_TRIGGER_ATTRIBUTE";
        public static final String ILLEGAL_TRIGGER_RELATION = "ILLEGAL_TRIGGER_RELATION";
        public static final String INVALID_STATE_FORMAT = "INVALID_STATE_FORMAT";
        public static final String ENV_VAR_CONFLICT = "ENV_VAR_CONFLICT";
        public static final String AMBIGUOUS_DEVICE_REFERENCE = "AMBIGUOUS_DEVICE_REFERENCE";
        public static final String TRUST_PRIVACY_CONFLICT = "TRUST_PRIVACY_CONFLICT";
        public static final String INCOMPLETE_TRIGGER = "INCOMPLETE_TRIGGER";
        public static final String PRIVACY_SPEC_WITHOUT_PRIVACY = "PRIVACY_SPEC_WITHOUT_PRIVACY";
        public static final String INVALID_BUILDER_INPUT = "INVALID_BUILDER_INPUT";
        public static final String TEMPLATE_LOAD_ERROR = "TEMPLATE_LOAD_ERROR";
    }
}
