package cn.edu.nju.Iot_Verify.dto.spec;

import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecificationDtoValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void deviceRef_withoutId_shouldReject() {
        SpecificationDto spec = validSpec();
        spec.setDevices(List.of(new SpecificationDto.DeviceRefDto(null, null, List.of())));

        Set<ConstraintViolation<SpecificationDto>> violations = validator.validate(spec);

        assertTrue(hasViolationContaining(violations, "Specification device ID is required"));
    }

    @Test
    void deviceRef_withLabelOnly_shouldReject() {
        SpecificationDto spec = validSpec();
        spec.setDevices(List.of(new SpecificationDto.DeviceRefDto(null, "Kitchen Light", List.of())));

        Set<ConstraintViolation<SpecificationDto>> violations = validator.validate(spec);

        assertTrue(hasViolationContaining(violations, "Specification device ID is required"));
    }

    @Test
    void condition_withUnsupportedSide_shouldReject() {
        SpecificationDto spec = validSpec();
        spec.setAConditions(List.of(validCondition("before")));

        Set<ConstraintViolation<SpecificationDto>> violations = validator.validate(spec);

        assertTrue(hasViolationContaining(violations, "Side must be one of: a, if, then"));
    }

    @Test
    void condition_nullElement_shouldReject() {
        SpecificationDto spec = validSpec();
        spec.setAConditions(Collections.singletonList(null));

        Set<ConstraintViolation<SpecificationDto>> violations = validator.validate(spec);

        assertTrue(hasViolationContaining(violations, "A-condition item cannot be null"));
    }

    @Test
    void condition_withoutId_shouldReject() {
        SpecificationDto spec = validSpec();
        SpecConditionDto condition = validCondition("a");
        condition.setDeviceId(null);
        condition.setDeviceLabel(null);
        spec.setAConditions(List.of(condition));

        Set<ConstraintViolation<SpecificationDto>> violations = validator.validate(spec);

        assertTrue(hasViolationContaining(violations, "Device ID is required for spec condition"));
    }

    @Test
    void condition_withLabelOnly_shouldReject() {
        SpecificationDto spec = validSpec();
        SpecConditionDto condition = validCondition("a");
        condition.setDeviceId(null);
        condition.setDeviceLabel("Kitchen Light");
        spec.setAConditions(List.of(condition));

        Set<ConstraintViolation<SpecificationDto>> violations = validator.validate(spec);

        assertTrue(hasViolationContaining(violations, "Device ID is required for spec condition"));
    }

    @Test
    void deviceRef_nullElement_shouldReject() {
        SpecificationDto spec = validSpec();
        spec.setDevices(Collections.singletonList(null));

        Set<ConstraintViolation<SpecificationDto>> violations = validator.validate(spec);

        assertTrue(hasViolationContaining(violations, "Specification device item cannot be null"));
    }

    @Test
    void selectedApis_nullElement_shouldReject() {
        SpecificationDto spec = validSpec();
        spec.setDevices(List.of(new SpecificationDto.DeviceRefDto("light", null, Collections.singletonList(null))));

        Set<ConstraintViolation<SpecificationDto>> violations = validator.validate(spec);

        assertTrue(hasViolationContaining(violations, "Selected API name cannot be null"));
    }

    @Test
    void condition_sideMustMatchContainingCollection() {
        SpecificationDto spec = validSpec();
        spec.setIfConditions(List.of(validCondition("then")));

        Set<ConstraintViolation<SpecificationDto>> violations = validator.validate(spec);

        assertTrue(hasViolationContaining(violations, "Specification condition side must match its containing collection"));
    }

    @Test
    void condition_sideMayBeOmittedBecauseCollectionIsAuthoritative() {
        SpecificationDto spec = validSpec();
        spec.setThenConditions(List.of(validCondition(null)));

        Set<ConstraintViolation<SpecificationDto>> violations = validator.validate(spec);

        assertFalse(hasViolationContaining(violations, "Side must be one of: a, if, then"));
        assertFalse(hasViolationContaining(violations, "Specification condition side must match its containing collection"));
    }

    @Test
    void condition_targetTypeMode_shouldPassTargetTypeValidation() {
        SpecificationDto spec = validSpec();
        SpecConditionDto condition = validCondition("a");
        condition.setTargetType("mode");
        condition.setKey("Mode");
        condition.setValue("sleep");
        spec.setAConditions(List.of(condition));

        Set<ConstraintViolation<SpecificationDto>> violations = validator.validate(spec);

        assertFalse(hasViolationContaining(violations, "targetType must be one of"));
    }

    @Test
    void condition_relationAliasWithWhitespace_shouldPassBoundaryValidation() {
        SpecificationDto spec = validSpec();
        SpecConditionDto condition = validCondition("a");
        condition.setRelation(" NOT_IN ");
        condition.setValue("on,off");
        spec.setAConditions(List.of(condition));

        Set<ConstraintViolation<SpecificationDto>> violations = validator.validate(spec);

        assertFalse(hasViolationContaining(violations, "Relation must be one of"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deviceId", "deviceLabel", "conditionLabel", "formula"})
    void authoredSpecificationRetainsInputLimitsForFieldsGeneratedInFrozenEvidence(String field) {
        SpecificationDto spec = validSpec();
        spec.setAConditions(List.of(validCondition("a")));
        spec.setDevices(List.of(new SpecificationDto.DeviceRefDto("light", "Light", List.of())));
        String expectedPath = switch (field) {
            case "deviceId" -> {
                spec.getDevices().get(0).setDeviceId("x".repeat(101));
                yield "devices[0].deviceId";
            }
            case "deviceLabel" -> {
                spec.getDevices().get(0).setDeviceLabel("L".repeat(101));
                yield "devices[0].deviceLabel";
            }
            case "conditionLabel" -> {
                spec.getAConditions().get(0).setDeviceLabel("L".repeat(201));
                yield "aConditions[0].deviceLabel";
            }
            case "formula" -> {
                spec.setFormula("F".repeat(4001));
                yield "formula";
            }
            default -> throw new AssertionError(field);
        };

        assertTrue(validator.validate(spec).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals(expectedPath)));
        assertTrue(validator.validate(spec, SpecificationDto.FrozenModel.class).isEmpty());
    }

    @Test
    void frozenDeviceReferencesUseModelLimitWhileAuthoringRetainsItsInputLimit() {
        SpecificationDto spec = validSpec();
        spec.setDevices(IntStream.range(0, RequestLimits.MAX_DEVICES)
                .mapToObj(index -> new SpecificationDto.DeviceRefDto("device-" + index, "Device", List.of()))
                .toList());

        assertTrue(hasViolationContaining(validator.validate(spec), "At most 50 devices"));
        assertTrue(validator.validate(spec, SpecificationDto.FrozenModel.class).isEmpty());

        spec.setDevices(Collections.nCopies(RequestLimits.MAX_DEVICES + 1, spec.getDevices().get(0)));
        assertTrue(hasViolationContaining(validator.validate(spec, SpecificationDto.FrozenModel.class),
                "Frozen specification references exceed the model device limit"));
    }

    @Test
    void missingNameIsRejectedForAuthoredAndFrozenSpecifications() {
        SpecificationDto spec = validSpec();
        spec.setName(null);
        assertTrue(validator.validate(spec).stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("name")));
        assertTrue(validator.validate(spec, SpecificationDto.FrozenModel.class).stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("name")));
    }

    private SpecificationDto validSpec() {
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_1");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");
        spec.setTemplateLabel("Always");
        return spec;
    }

    private SpecConditionDto validCondition(String side) {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setId("cond_1");
        condition.setSide(side);
        condition.setDeviceId("light");
        condition.setTargetType("state");
        condition.setKey("state");
        condition.setRelation("=");
        condition.setValue("on");
        return condition;
    }

    private boolean hasViolationContaining(Set<? extends ConstraintViolation<?>> violations, String substring) {
        return violations.stream().anyMatch(v -> v.getMessage().contains(substring));
    }
}
