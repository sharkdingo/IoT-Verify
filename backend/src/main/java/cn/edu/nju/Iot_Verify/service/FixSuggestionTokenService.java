package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.configure.JwtConfig;
import cn.edu.nju.Iot_Verify.dto.fix.ConditionAdjustment;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.fix.ParameterAdjustment;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRange;
import cn.edu.nju.Iot_Verify.exception.FixSuggestionStaleException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Signs the exact user-visible fix suggestion so apply cannot silently substitute another one. */
@Component
@RequiredArgsConstructor
public class FixSuggestionTokenService {

    // Previously issued suggestions were proved under older shared-value transition semantics.
    private static final int TOKEN_VERSION = 3;
    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final JwtConfig jwtConfig;
    private final Clock clock = Clock.systemUTC();

    public String issue(Long userId, Long traceId, FixSuggestionDto suggestion,
                        Map<String, PreferredRange> preferredRanges) {
        try {
            TokenPayload payload = new TokenPayload(
                    TOKEN_VERSION,
                    userId,
                    traceId,
                    suggestion.getStrategy(),
                    Instant.now(clock).plus(TOKEN_TTL).toEpochMilli(),
                    suggestionDigest(suggestion, preferredRanges),
                    parameterLocators(suggestion),
                    conditionLocators(suggestion),
                    suggestion.getRemovedRuleIndices() == null
                            ? List.of() : List.copyOf(suggestion.getRemovedRuleIndices()));
            String encodedPayload = ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload));
            return encodedPayload + "." + ENCODER.encodeToString(sign(encodedPayload));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign fix suggestion", e);
        }
    }

    public FixSuggestionDto verify(Long userId, Long traceId, String strategy,
                                   FixSuggestionDto suggestion, String token,
                                   Map<String, PreferredRange> preferredRanges) {
        if (suggestion == null || token == null || token.isBlank()) {
            throw new FixSuggestionStaleException();
        }
        try {
            String[] parts = token.trim().split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), DECODER.decode(parts[1]))) {
                throw new FixSuggestionStaleException();
            }
            TokenPayload payload = objectMapper.readValue(DECODER.decode(parts[0]), TokenPayload.class);
            if (payload.version() != TOKEN_VERSION
                    || !userId.equals(payload.userId())
                    || !traceId.equals(payload.traceId())
                    || !strategy.equals(payload.strategy())
                    || Instant.now(clock).toEpochMilli() > payload.expiresAt()
                    || !MessageDigest.isEqual(
                            payload.suggestionDigest().getBytes(StandardCharsets.UTF_8),
                            suggestionDigest(suggestion, preferredRanges).getBytes(StandardCharsets.UTF_8))) {
                throw new FixSuggestionStaleException();
            }
            FixSuggestionDto trusted = objectMapper.treeToValue(suggestionNode(suggestion), FixSuggestionDto.class);
            restoreInternalLocators(trusted, payload);
            trusted.setRemovedRuleIndices(payload.removedRuleIndices());
            trusted.setSuggestionToken(null);
            return trusted;
        } catch (FixSuggestionStaleException e) {
            throw e;
        } catch (Exception e) {
            throw new FixSuggestionStaleException();
        }
    }

    private String suggestionDigest(FixSuggestionDto suggestion,
                                    Map<String, PreferredRange> preferredRanges) throws Exception {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("suggestion", suggestionNode(suggestion));
        canonical.put("preferredRanges", preferredRanges == null
                ? Map.of() : new TreeMap<>(preferredRanges));
        return ENCODER.encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(objectMapper.writeValueAsBytes(canonical)));
    }

    private JsonNode suggestionNode(FixSuggestionDto suggestion) {
        ObjectNode node = objectMapper.valueToTree(suggestion);
        node.remove("suggestionToken");
        return node;
    }

    private List<ParameterLocator> parameterLocators(FixSuggestionDto suggestion) {
        List<ParameterAdjustment> adjustments = suggestion.getParameterAdjustments();
        if (adjustments == null) {
            return List.of();
        }
        return adjustments.stream()
                .map(adjustment -> new ParameterLocator(
                        adjustment.getRuleIndex(), adjustment.getConditionIndex()))
                .toList();
    }

    private List<ConditionLocator> conditionLocators(FixSuggestionDto suggestion) {
        List<ConditionAdjustment> adjustments = suggestion.getConditionAdjustments();
        if (adjustments == null) {
            return List.of();
        }
        return adjustments.stream()
                .map(adjustment -> new ConditionLocator(
                        adjustment.getRuleIndex(), adjustment.getConditionIndex(), adjustment.getDeviceName()))
                .toList();
    }

    private void restoreInternalLocators(FixSuggestionDto trusted, TokenPayload payload) {
        List<ParameterAdjustment> parameters = trusted.getParameterAdjustments() == null
                ? List.of() : trusted.getParameterAdjustments();
        List<ConditionAdjustment> conditions = trusted.getConditionAdjustments() == null
                ? List.of() : trusted.getConditionAdjustments();
        List<ParameterLocator> parameterLocators = payload.parameterLocators() == null
                ? List.of() : payload.parameterLocators();
        List<ConditionLocator> conditionLocators = payload.conditionLocators() == null
                ? List.of() : payload.conditionLocators();
        if (parameters.size() != parameterLocators.size()
                || conditions.size() != conditionLocators.size()
                || payload.removedRuleIndices() == null) {
            throw new FixSuggestionStaleException();
        }
        for (int i = 0; i < parameters.size(); i++) {
            ParameterLocator locator = parameterLocators.get(i);
            if (locator == null || locator.ruleIndex() < 0 || locator.conditionIndex() < 0) {
                throw new FixSuggestionStaleException();
            }
            parameters.get(i).setRuleIndex(locator.ruleIndex());
            parameters.get(i).setConditionIndex(locator.conditionIndex());
        }
        for (int i = 0; i < conditions.size(); i++) {
            ConditionLocator locator = conditionLocators.get(i);
            if (locator == null || locator.ruleIndex() < 0 || locator.conditionIndex() < 0) {
                throw new FixSuggestionStaleException();
            }
            conditions.get(i).setRuleIndex(locator.ruleIndex());
            conditions.get(i).setConditionIndex(locator.conditionIndex());
            conditions.get(i).setDeviceName(locator.deviceName());
        }
    }

    private byte[] sign(String encodedPayload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(encodedPayload.getBytes(StandardCharsets.US_ASCII));
    }

    private record TokenPayload(int version, Long userId, Long traceId, String strategy, long expiresAt,
                                String suggestionDigest,
                                List<ParameterLocator> parameterLocators,
                                List<ConditionLocator> conditionLocators,
                                List<Integer> removedRuleIndices) {
    }

    private record ParameterLocator(int ruleIndex, int conditionIndex) {
    }

    private record ConditionLocator(int ruleIndex, int conditionIndex, String deviceName) {
    }
}
