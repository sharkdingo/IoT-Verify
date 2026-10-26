package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Target versions for AI edits; display projections never define the concurrency baseline. */
public final class BoardItemVersion {
    public static final String PATTERN = "^v1:[0-9a-f]{64}$";

    private BoardItemVersion() {
    }

    public static String rule(Long userId, RuleDto rule) {
        Objects.requireNonNull(rule.getId(), "A saved rule is required");
        return version(new VersionInput(Objects.requireNonNull(userId), "rule", rule.getId().toString(),
                new RuleState(rule.getCreatedAt(), rule.getRuleString(), rule.getConditions(), rule.getCommand())));
    }

    public static String specification(Long userId, SpecificationDto specification) {
        return version(new VersionInput(Objects.requireNonNull(userId), "specification",
                Objects.requireNonNull(specification.getId(), "A saved specification is required"),
                new SpecificationState(specification.getName().trim(), SpecificationSemanticSignature.from(specification))));
    }

    private static String version(VersionInput input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(JsonUtils.toJson(input).getBytes(StandardCharsets.UTF_8));
            return "v1:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private record SpecificationState(String name, SpecificationSemanticSignature.Signature logic) {}

    private record VersionInput(Long userId, String kind, String id, Object state) {
    }

    private record RuleState(LocalDateTime createdAt, String label,
                             List<RuleDto.Condition> conditions, RuleDto.Command command) {
    }
}
