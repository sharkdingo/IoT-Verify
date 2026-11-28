package cn.edu.nju.Iot_Verify.component.fuzz.paper;

import java.util.Objects;

/**
 * One immutable event in the paper-compatible seed representation.
 *
 * <p>A device value is a legal device mode. An environment value is normally a change
 * rate; direct environment values belong to separately materialized initial state. A
 * rate-free discrete environment may use direct values as a compatibility extension.</p>
 */
public record PaperEvent(Type type, String name, String value, int position) {

    public enum Type {
        DEVICE,
        ENVIRONMENT
    }

    public PaperEvent {
        type = Objects.requireNonNull(type, "type must not be null");
        name = requireText(name, "name");
        value = requireText(value, "value");
        if (position < 1) {
            throw new IllegalArgumentException("position must be at least 1");
        }
        if (type == Type.DEVICE && position != 1) {
            throw new IllegalArgumentException("device events are only legal at position 1");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
