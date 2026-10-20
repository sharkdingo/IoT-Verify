package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelRequestSnapshotSupportTest {

    @Test
    void preservesExactServerOwnedTokenProvenance() {
        DeviceVerificationDto source = device(ModelTokenSource.BUNDLED);
        DeviceVerificationDto snapshot = device(ModelTokenSource.UNKNOWN);

        ModelRequestSnapshotSupport.preserveDeviceMetadata(
                List.of(source), List.of(snapshot));

        assertEquals(ModelTokenSource.BUNDLED, snapshot.getModelTokenSource());
    }

    @Test
    void rejectsSnapshotShapeAndProvenanceDrift() {
        DeviceVerificationDto source = device(ModelTokenSource.CUSTOM);
        assertThrows(IllegalArgumentException.class,
                () -> ModelRequestSnapshotSupport.preserveDeviceMetadata(
                        List.of(source), List.of()));

        source.setModelTokenSource(null);
        assertThrows(IllegalArgumentException.class,
                () -> ModelRequestSnapshotSupport.preserveDeviceMetadata(
                        List.of(source), List.of(device(ModelTokenSource.UNKNOWN))));

        assertThrows(IllegalArgumentException.class,
                () -> ModelRequestSnapshotSupport.preserveDeviceMetadata(null, List.of()));
    }

    @Test
    void preservesEquivalentMissingListsForRequestValidation() {
        assertDoesNotThrow(() -> ModelRequestSnapshotSupport.preserveDeviceMetadata(null, null));
    }

    @Test
    void collectionCopiesRetainRequiredAndOptionalValidationPolicies() {
        ValidationException missing = assertThrows(ValidationException.class,
                () -> ModelRequestSnapshotSupport.copyRequiredList(null, "specs", "Add a specification"));
        assertEquals("Add a specification", missing.getErrors().get("specs"));
        assertEquals(List.of(), ModelRequestSnapshotSupport.copyOptionalList(null, "rules"));

        List<String> source = new ArrayList<>(List.of("rule"));
        List<String> copy = ModelRequestSnapshotSupport.copyOptionalList(source, "rules");
        source.clear();
        assertEquals(List.of("rule"), copy);

        ValidationException nullItem = assertThrows(ValidationException.class,
                () -> ModelRequestSnapshotSupport.copyOptionalList(Arrays.asList("rule", null), "rules"));
        assertEquals("must not be null", nullItem.getErrors().get("rules[1]"));
    }

    private DeviceVerificationDto device(ModelTokenSource source) {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("device-1");
        device.setTemplateName("Switch");
        device.setModelTokenSource(source);
        return device;
    }
}
