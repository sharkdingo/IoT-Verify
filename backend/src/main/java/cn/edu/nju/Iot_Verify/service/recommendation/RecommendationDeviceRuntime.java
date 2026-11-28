package cn.edu.nju.Iot_Verify.service.recommendation;

import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest.InternalVariable;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class RecommendationDeviceRuntime {
    private RecommendationDeviceRuntime() {}

    static Map<String, InternalVariable> indexLocalVariables(DeviceTemplateDto template) {
        Map<String, InternalVariable> variables = new LinkedHashMap<>();
        if (template == null || template.getManifest() == null
                || template.getManifest().getInternalVariables() == null) {
            return variables;
        }
        for (InternalVariable variable : template.getManifest().getInternalVariables()) {
            if (variable.getName() != null && Boolean.TRUE.equals(variable.getIsInside())) {
                variables.put(variable.getName().toLowerCase(Locale.ROOT), variable);
            }
        }
        return variables;
    }

    static List<Map<String, Object>> readLocalPrivacies(
            DeviceTemplateDto template, JsonNode rows, boolean rejectBlankNames) {
        if (!rows.isNull() && !rows.isMissingNode() && !rows.isArray()) return null;
        Map<String, InternalVariable> variables = indexLocalVariables(template);
        List<Map<String, Object>> privacies = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode row : rows) {
            if (!row.isObject()) return null;
            String key = RecommendationProviderOutput.candidateText(row.path("name")).toLowerCase(Locale.ROOT);
            if (rejectBlankNames && key.isBlank()) return null;
            InternalVariable variable = variables.get(key);
            if (variable == null || !seen.add(key)) return null;
            JsonNode privacyNode = row.path("privacy");
            String privacy = normalizePrivacy(privacyNode.asText(""));
            if (!privacyNode.isNull() && !privacyNode.isMissingNode() && privacy == null) return null;
            if (privacy != null) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", variable.getName());
                item.put("privacy", privacy);
                privacies.add(item);
            }
        }
        return privacies;
    }

    static String normalizeTrust(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return "trusted".equals(normalized) || "untrusted".equals(normalized) ? normalized : null;
    }

    static String normalizePrivacy(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return "public".equals(normalized) || "private".equals(normalized) ? normalized : null;
    }
}
