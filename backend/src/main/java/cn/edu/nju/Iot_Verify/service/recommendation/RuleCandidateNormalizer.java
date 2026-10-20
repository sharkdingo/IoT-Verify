package cn.edu.nju.Iot_Verify.service.recommendation;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One typed candidate contract for REST rule checks and parsed tool arguments. */
public final class RuleCandidateNormalizer {
    private static final Set<String> TARGET_TYPES = Set.of("api", "variable", "mode", "state");
    private static final Set<String> ENUM_RELATIONS = Set.of("=", "!=", "in", "not in");

    private RuleCandidateNormalizer() {
    }

    public static RuleDto normalize(RuleDto candidate) {
        if (candidate == null) throw new BadRequestException("newRule is required.");
        if (candidate.getId() != null && candidate.getId() <= 0) {
            throw new BadRequestException("newRule.id must be a positive integer when provided.");
        }
        if (candidate.getConditions() == null || candidate.getConditions().isEmpty()) {
            throw new BadRequestException("newRule.conditions must be a non-empty array.");
        }
        List<RuleDto.Condition> conditions = new ArrayList<>();
        int number = 1;
        for (RuleDto.Condition raw : candidate.getConditions()) {
            String path = "newRule condition " + number++;
            if (raw == null) throw new BadRequestException(path + " is required.");
            String deviceName = requiredText(raw.getDeviceName(), "deviceName", path);
            String attribute = requiredText(raw.getAttribute(), "attribute", path);
            String targetType = requiredText(raw.getTargetType(), "targetType", path).toLowerCase(Locale.ROOT);
            if (!TARGET_TYPES.contains(targetType)) {
                throw new BadRequestException(path + ".targetType must be one of api, variable, mode, state.");
            }
            String relation = optionalText(raw.getRelation());
            String value = optionalText(raw.getValue());
            if ("api".equals(targetType)) {
                if (relation != null || value != null) {
                    throw new BadRequestException(path + " is an API signal and must omit relation and value.");
                }
            } else {
                if (relation == null || value == null) {
                    throw new BadRequestException(path + " must include textual relation and value.");
                }
                relation = SmvRelationUtils.normalizeRelation(relation);
                if (!SmvRelationUtils.isSupportedRelation(relation)) {
                    throw new BadRequestException(path + ".relation must be one of =, !=, >, >=, <, <=, in, not in.");
                }
                if (("state".equals(targetType) || "mode".equals(targetType)) && !ENUM_RELATIONS.contains(relation)) {
                    throw new BadRequestException(path + " uses state/mode semantics and only supports =, !=, in, not in.");
                }
                if (("in".equals(relation) || "not in".equals(relation)) && hasNoListItem(value)) {
                    throw new BadRequestException(path + ".value must contain at least one item for relation " + relation + ".");
                }
            }
            conditions.add(RuleDto.Condition.builder()
                    .deviceName(deviceName).attribute(attribute).targetType(targetType)
                    .relation(relation).value(value).build());
        }
        RuleDto.Command raw = candidate.getCommand();
        if (raw == null) throw new BadRequestException("newRule.command is required.");
        String commandDevice = requiredText(raw.getDeviceName(), "deviceName", "newRule.command");
        String action = requiredText(raw.getAction(), "action", "newRule.command");
        String contentDevice = optionalText(raw.getContentDevice());
        String content = optionalText(raw.getContent());
        if ((contentDevice == null) != (content == null)) {
            throw new BadRequestException("newRule.command.contentDevice and content must be provided together.");
        }
        return RuleDto.builder().id(candidate.getId()).userId(candidate.getUserId()).createdAt(candidate.getCreatedAt())
                .conditions(conditions)
                .command(RuleDto.Command.builder().deviceName(commandDevice).action(action)
                        .contentDevice(contentDevice).content(content).build())
                .ruleString(optionalText(candidate.getRuleString())).build();
    }

    private static String requiredText(String raw, String field, String path) {
        String value = optionalText(raw);
        if (value == null) {
            throw new BadRequestException(path + "." + field + " is required and must be a non-empty string.");
        }
        return value;
    }

    private static String optionalText(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean hasNoListItem(String value) {
        for (String item : value.split("[,;|]")) {
            if (!item.trim().isEmpty()) return false;
        }
        return true;
    }
}
