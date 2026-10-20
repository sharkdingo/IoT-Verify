package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.exception.ValidationException;

import java.util.ArrayList;
import java.util.List;

/** Preserves server metadata and rejects malformed collections in frozen request snapshots. */
final class ModelRequestSnapshotSupport {

    private ModelRequestSnapshotSupport() {
    }

    static void preserveDeviceMetadata(List<DeviceVerificationDto> source,
                                       List<DeviceVerificationDto> snapshot) {
        if (source == null && snapshot == null) {
            return;
        }
        if (source == null || snapshot == null || source.size() != snapshot.size()) {
            throw new IllegalArgumentException("Snapshot device count does not match its source");
        }
        for (int index = 0; index < source.size(); index++) {
            DeviceVerificationDto original = source.get(index);
            DeviceVerificationDto copy = snapshot.get(index);
            if (original == null || copy == null || original.getModelTokenSource() == null) {
                throw new IllegalArgumentException(
                        "Snapshot device metadata is incomplete at index " + index);
            }
            ModelTokenSource tokenSource = original.getModelTokenSource();
            copy.setModelTokenSource(tokenSource);
        }
    }

    static <T> List<T> copyRequiredList(List<T> values, String field, String emptyMessage) {
        if (values == null || values.isEmpty()) {
            throw new ValidationException(field, emptyMessage);
        }
        return copyList(values, field);
    }

    static <T> List<T> copyOptionalList(List<T> values, String field) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return copyList(values, field);
    }

    private static <T> List<T> copyList(List<T> values, String field) {
        List<T> copy = new ArrayList<>(values);
        for (int i = 0; i < copy.size(); i++) {
            if (copy.get(i) == null) {
                throw new ValidationException(field + "[" + i + "]", "must not be null");
            }
        }
        return copy;
    }
}
