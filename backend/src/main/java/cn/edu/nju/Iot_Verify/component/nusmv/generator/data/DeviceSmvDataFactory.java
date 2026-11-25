package cn.edu.nju.Iot_Verify.component.nusmv.generator.data;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvModelValidator;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.device.PrivacyStateDto;
import cn.edu.nju.Iot_Verify.component.template.DeviceManifestModes;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import cn.edu.nju.Iot_Verify.service.DeviceTemplateService;
import cn.edu.nju.Iot_Verify.util.DeviceNameNormalizer;
import cn.edu.nju.Iot_Verify.util.EnvironmentDomainUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * DeviceSmvData 工厂
 * 职责：从用户输入 + 设备模板构建 DeviceSmvData 映射
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceSmvDataFactory {

    private final ObjectMapper objectMapper;
    private final DeviceTemplateService deviceTemplateService;
    private final SmvModelValidator modelValidator;
    private final DeviceTemplateSchemaValidator templateSchemaValidator;

    // ==================== 公共入口 ====================

    public Map<String, DeviceSmvData> buildDeviceSmvMap(Long userId,
                                                        List<DeviceVerificationDto> devices) {
        return buildDeviceSmvMap(userId, devices, new HashMap<>());
    }

    public Map<String, DeviceSmvData> buildDeviceSmvMap(Long userId,
                                                        List<DeviceVerificationDto> devices,
                                                        Map<String, DeviceManifest> templateCache) {
        return buildDeviceSmvMap(userId, devices, templateCache, new HashMap<>(), false);
    }

    /** Build from an immutable run snapshot and never fall back to the mutable template repository. */
    public Map<String, DeviceSmvData> buildDeviceSmvMapFromTemplateSnapshots(
            List<DeviceVerificationDto> devices,
            Map<String, DeviceManifest> templateSnapshots) {
        if (templateSnapshots == null) {
            throw SmvGenerationException.smvGenerationError("Captured device-template manifests are missing");
        }
        return buildDeviceSmvMap(null, devices, new LinkedHashMap<>(templateSnapshots), Map.of(), true);
    }

    private Map<String, DeviceSmvData> buildDeviceSmvMap(Long userId,
                                                         List<DeviceVerificationDto> devices,
                                                         Map<String, DeviceManifest> templateCache,
                                                         Map<String, ModelTokenSource> templateSourceCache,
                                                         boolean snapshotOnly) {
        Map<String, DeviceSmvData> deviceSmvMap = new LinkedHashMap<>();
        List<String> missingTemplateDevices = new ArrayList<>();
        for (DeviceVerificationDto device : devices) {
            if (device == null || device.getVarName() == null || device.getVarName().isBlank()
                    || device.getTemplateName() == null || device.getTemplateName().isBlank()) {
                continue;
            }
            DeviceSmvData smv = new DeviceSmvData();
            smv.setTemplateName(device.getTemplateName());
            smv.setDeviceLabel(displayLabel(device));
            smv.setModelTokenSource(snapshotOnly
                    ? safeModelTokenSource(device.getModelTokenSource())
                    : ModelTokenSource.UNKNOWN);
            smv.setCurrentState(device.getState());
            smv.setInstanceStateTrust(normalizeTrustPrivacy(device.getCurrentStateTrust()));
            smv.setInstanceStatePrivacy(normalizeTrustPrivacy(device.getCurrentStatePrivacy()));

            if (device.getVariables() != null) {
                for (VariableStateDto var : device.getVariables()) {
                    if (var.getName() != null && var.getValue() != null)
                        smv.getVariableValues().put(var.getName(), var.getValue());
                    if (var.getName() != null && var.getTrust() != null)
                        smv.getInstanceVariableTrust().put(var.getName(), normalizeTrustPrivacy(var.getTrust()));
                }
            }
            if (device.getPrivacies() != null) {
                for (PrivacyStateDto p : device.getPrivacies()) {
                    if (p.getName() != null && p.getPrivacy() != null)
                        smv.getInstanceVariablePrivacy().put(p.getName(), normalizeTrustPrivacy(p.getPrivacy()));
                }
            }

            DeviceManifest manifest = loadManifest(
                    templateCache, templateSourceCache, userId, smv.getTemplateName(), snapshotOnly);
            if (manifest == null) {
                log.warn("Template not found or invalid for device: {}", smv.getTemplateName());
                missingTemplateDevices.add(device.getVarName() + "(template=" + smv.getTemplateName() + ")");
                continue;
            }
            smv.setManifest(manifest);
            smv.setAuthoredStatesByMode(DeviceManifestModes.authoredStatesByMode(manifest,
                    reason -> SmvGenerationException.smvGenerationError(
                            "Device '" + device.getVarName() + "': " + reason)));
            if (!snapshotOnly) {
                smv.setModelTokenSource(templateSourceCache.getOrDefault(
                        smv.getTemplateName(), ModelTokenSource.UNKNOWN));
            }
            device.setModelTokenSource(smv.getModelTokenSource());

            extractModes(smv, manifest);
            extractStatesAndTrust(smv, manifest);
            parseModeStates(smv, manifest.getInitState(), smv.getTemplateInitModeStates());
            parseModeStates(smv, smv.getCurrentState(), smv.getCurrentModeStates());

            if (manifest.getInternalVariables() != null) smv.getVariables().addAll(manifest.getInternalVariables());
            extractSignalVars(smv, manifest);
            if (manifest.getImpactedVariables() != null) smv.getImpactedVariables().addAll(manifest.getImpactedVariables());
            extractEnvVariables(smv, manifest);
            extractSharedDeclarations(smv, manifest);
            extractImpactedEnvironmentVariables(smv, manifest);
            extractContents(smv, manifest);
            computeIdentifiers(smv, device.getVarName());
            assertCanonicalVarName(device.getVarName(), smv.getVarName());

            // P2-2: 检测归一化后变量名冲突
            for (DeviceSmvData existing : deviceSmvMap.values()) {
                if (smv.getVarName().equals(existing.getVarName())) {
                    throw SmvGenerationException.smvGenerationError(
                            "Variable name collision: '" + device.getVarName()
                            + "' and another device both normalize to '" + smv.getVarName() + "'");
                }
            }

            // 软性校验：委托给 SmvModelValidator（集中管理）
            modelValidator.warnUnknownUserVariables(smv, device);
            modelValidator.warnStatelessDeviceWithState(smv, device);

            deviceSmvMap.put(smv.getVarName(), smv);
        }

        if (!missingTemplateDevices.isEmpty()) {
            throw SmvGenerationException.multipleDevicesFailed(String.join(", ", missingTemplateDevices));
        }

        return deviceSmvMap;
    }

    // ==================== 模板加载 ====================

    private DeviceManifest loadManifest(Map<String, DeviceManifest> cache,
                                        Map<String, ModelTokenSource> sourceCache,
                                        Long userId,
                                        String templateName, boolean snapshotOnly) {
        if (templateName == null) return null;
        if (cache.containsKey(templateName)) return cache.get(templateName);
        if (snapshotOnly) return null;
        try {
            Optional<DeviceTemplatePo> po = deviceTemplateService.findTemplateByName(userId, templateName);
            if (po.isEmpty()) return null;
            JsonNode rawManifest = objectMapper.readTree(po.get().getManifestJson());
            templateSchemaValidator.validateRawManifest(templateName, rawManifest);
            DeviceManifest manifest = objectMapper.treeToValue(rawManifest, DeviceManifest.class);
            cache.put(templateName, manifest);
            sourceCache.put(templateName,
                    ModelTokenSource.fromDefaultTemplate(po.get().getDefaultTemplate()));
            return manifest;
        } catch (BaseException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw SmvGenerationException.manifestParseError(templateName, e);
        } catch (Exception e) {
            throw SmvGenerationException.templateLoadError(templateName, e);
        }
    }

    private ModelTokenSource safeModelTokenSource(ModelTokenSource source) {
        return source != null ? source : ModelTokenSource.UNKNOWN;
    }

    // ==================== 提取方法 ====================

    private void extractModes(DeviceSmvData smv, DeviceManifest manifest) {
        if (manifest.getModes() != null && !manifest.getModes().isEmpty()) {
            for (String rawMode : manifest.getModes()) {
                String mode = sanitizeSmvToken(rawMode);
                smv.getModes().add(mode);
                String authored = rawMode.trim();
                if (!mode.equals(authored)) {
                    smv.getRenamedModes().put(mode, authored);
                }
                smv.getModeStates().put(mode, new ArrayList<>());
            }
        }
    }

    /** Bind template and instance state segments against the same authored mode domains. */
    private void parseModeStates(DeviceSmvData smv, String state, Map<String, String> modeStates) {
        if (state == null || state.isBlank()) return;
        String[] parts = state.split(";", -1);
        for (int i = 0; i < parts.length && i < smv.getModes().size(); i++) {
            if (isWildcardStateSegment(parts[i])) continue;
            String mode = smv.getModes().get(i);
            String token = smv.resolveAuthoredState(mode, parts[i]);
            if (token != null) {
                modeStates.put(mode, token);
            }
        }
    }

    private void extractStatesAndTrust(DeviceSmvData smv, DeviceManifest manifest) {
        if (manifest.getWorkingStates() == null) return;
        for (DeviceManifest.WorkingState ws : manifest.getWorkingStates()) {
            if (ws.getName() == null) continue;
            smv.getStates().add(sanitizeSmvToken(ws.getName().trim()));
            String[] parts = ws.getName().split(";", -1);
            for (int i = 0; i < parts.length && i < smv.getModes().size(); i++) {
                String mode = smv.getModes().get(i);
                String stateName = cleanStateName(parts[i]);
                if (stateName.isEmpty()) continue;
                List<String> modeStateList = smv.getModeStates().get(mode);
                if (!modeStateList.contains(stateName)) {
                    modeStateList.add(stateName);
                }
                String key = mode + "_" + stateName;
                if (ws.getTrust() != null) smv.getModeStateTrust().put(key, normalizeTrustPrivacy(ws.getTrust()));
            }
        }
    }

    private void extractSignalVars(DeviceSmvData smv, DeviceManifest manifest) {
        if (manifest.getApis() != null) {
            for (DeviceManifest.API api : manifest.getApis()) {
                if (api.getSignal() != null && api.getSignal()) {
                    DeviceSmvData.SignalInfo sig = new DeviceSmvData.SignalInfo();
                    sig.setName(formatApiSignalName(api.getName()));
                    smv.getSignalVars().add(sig);
                }
            }
        }
    }

    /**
     * Shared values this device <em>reads</em>. This map is the rule/spec source-capability set, so a
     * declaration that only affects a shared value ({@code Reads=false}) must not land here — that is
     * the distinction the separate {@code EnvironmentDomains} array used to encode by its mere
     * existence. Omitting {@code Reads} keeps the historical meaning of a shared declaration: it reads.
     */
    private void extractEnvVariables(DeviceSmvData smv, DeviceManifest manifest) {
        if (manifest.getInternalVariables() == null) return;
        for (DeviceManifest.InternalVariable iv : manifest.getInternalVariables()) {
            if ((iv.getIsInside() == null || !iv.getIsInside())
                    && !Boolean.FALSE.equals(iv.getReads())) {
                smv.getEnvVariables().put(iv.getName(), iv);
            }
        }
    }

    /** Every shared declaration, whether read or affect-only, for domain resolution. */
    private void extractSharedDeclarations(DeviceSmvData smv, DeviceManifest manifest) {
        if (manifest.getInternalVariables() == null) return;
        for (DeviceManifest.InternalVariable iv : manifest.getInternalVariables()) {
            if (iv.getIsInside() == null || !iv.getIsInside()) {
                smv.getSharedDeclarations().put(iv.getName(), iv);
            }
        }
    }

    private void extractImpactedEnvironmentVariables(DeviceSmvData smv, DeviceManifest manifest) {
        if (smv.getImpactedVariables() == null || smv.getImpactedVariables().isEmpty()) {
            return;
        }
        for (String impacted : smv.getImpactedVariables()) {
            if (impacted == null || impacted.isBlank()) {
                continue;
            }
            // Any shared declaration supplies the domain, including an affect-only one that
            // deliberately carries no read capability.
            DeviceManifest.InternalVariable definition =
                    smv.getSharedDeclarations().get(impacted);
            if (definition == null) {
                definition = EnvironmentDomainUtils.resolveImpactDomain(manifest, impacted);
            }
            if (definition != null) {
                smv.getImpactedEnvironmentVariables().put(impacted, definition);
            }
        }
    }

    private void extractContents(DeviceSmvData smv, DeviceManifest manifest) {
        if (manifest.getContents() == null) return;
        for (DeviceManifest.Content c : manifest.getContents()) {
            if (c.getName() == null || c.getName().isBlank()) continue;
            DeviceSmvData.ContentInfo info = new DeviceSmvData.ContentInfo();
            info.setName(c.getName());
            info.setPrivacy(normalizeTrustPrivacy(c.getPrivacy()));
            smv.getContents().add(info);
        }
    }

    // ==================== NuSMV 保留字 ====================

    /**
     * NuSMV reserved words — identifiers matching these (case-insensitive) must be escaped
     * by prepending '_' to avoid syntax errors in generated SMV models.
     */
    public static final Set<String> NUSMV_RESERVED_WORDS = DeviceNameNormalizer.NUSMV_RESERVED_WORDS;

    // ==================== 工具方法 ====================

    private void computeIdentifiers(DeviceSmvData smv, String rawVarName) {
        // Use one normalization pipeline for both varName and module suffix
        // so module identity cannot diverge from instance identity.
        String result = DeviceNameNormalizer.normalize(rawVarName);
        if (result == null || result.isBlank()) {
            result = "device_0";
        }
        smv.setVarName(result);

        // moduleName
        String tplName = smv.getTemplateName() != null ? smv.getTemplateName() : "Device";
        String base = tplName.replaceAll("[^a-zA-Z0-9]", "");
        if (base.isEmpty()) base = "Device";
        // NuSMV identifiers must not start with a digit
        if (Character.isDigit(base.charAt(0))) {
            base = "_" + base;
        }
        // NuSMV reserved-word guard on base
        if (NUSMV_RESERVED_WORDS.contains(base)
                || NUSMV_RESERVED_WORDS.contains(base.toUpperCase(Locale.ROOT))
                || NUSMV_RESERVED_WORDS.contains(base.toLowerCase(Locale.ROOT))) {
            base = "_" + base;
        }
        String suffix = result;
        // NuSMV reserved-word guard on suffix (defense-in-depth, result is already checked)
        if (NUSMV_RESERVED_WORDS.contains(suffix)
                || NUSMV_RESERVED_WORDS.contains(suffix.toUpperCase(Locale.ROOT))
                || NUSMV_RESERVED_WORDS.contains(suffix.toLowerCase(Locale.ROOT))) {
            suffix = "_" + suffix;
        }
        // NuSMV identifiers should not start with a digit.
        if (!suffix.isEmpty() && Character.isDigit(suffix.charAt(0))) {
            suffix = "_" + suffix;
        }
        smv.setModuleName(base + "_" + suffix);

        // sensor
        smv.setSensor(smv.getManifest() != null
                && (smv.getManifest().getApis() == null || smv.getManifest().getApis().isEmpty()));
    }

    private String displayLabel(DeviceVerificationDto device) {
        String label = device.getDeviceLabel();
        return label == null || label.isBlank() ? device.getVarName() : label.trim();
    }

    private void assertCanonicalVarName(String requestedVarName, String canonicalVarName) {
        if (!Objects.equals(requestedVarName, canonicalVarName)) {
            throw SmvGenerationException.smvGenerationError(
                    "Device varName must be the canonical NuSMV-safe node id: '"
                            + requestedVarName + "' normalizes to '" + canonicalVarName + "'");
        }
    }

    public static String formatApiSignalName(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("[^a-zA-Z0-9_]", "_");
        if (cleaned.isBlank()) return null;
        return cleaned + "_a";
    }

    /**
     * Defensive sanitization for template mode/state tokens.
     * Removes spaces, replaces non-alphanumeric/underscore chars with '_',
     * prepends '_' if starting with digit.
     */
    /**
     * The one function that decides what token a mode/state name becomes in the generated model.
     *
     * <p>Public because {@code DeviceTemplateNuSmvValidator} has to reject collisions in terms of *this* output,
     * not the raw name. Comparing raw names let modes {@code next} and {@code _next} pass as distinct while both
     * sanitize to {@code _next}, and NuSMV then rejects the model with "duplicate constants in the enum type of
     * variable" — a type error surfaced to the user as an engine failure rather than a fixable template problem.
     */
    public static String sanitizeSmvToken(String raw) {
        if (raw == null) return null;
        String s = raw.replace(" ", "").replaceAll("[^a-zA-Z0-9_]", "_");
        if (s.isEmpty()) return "_";
        if (Character.isDigit(s.charAt(0))) s = "_" + s;
        if (NUSMV_RESERVED_WORDS.contains(s)
                || NUSMV_RESERVED_WORDS.contains(s.toUpperCase(Locale.ROOT))
                || NUSMV_RESERVED_WORDS.contains(s.toLowerCase(Locale.ROOT))) s = "_" + s;
        return s;
    }

    /**
     * Normalize trust/privacy values to lowercase for NuSMV enum compatibility.
     * NuSMV enums are defined as {untrusted, trusted} / {private, public} (lowercase).
     */
    public static String normalizeTrustPrivacy(String value) {
        if (value == null) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    // ==================== 静态工具方法（原 SmvUtils，供 builder 使用） ====================

    /** 获取 state 所属的 mode 索引 */
    public static int getModeIndexOfState(DeviceSmvData smv, String state) {
        if (smv == null || smv.getModes() == null || state == null) return -1;
        // 优先：检查 state 是否以 mode 名作为独立词前缀（如 "ThermostatMode_cool"）
        // 使用精确匹配或 mode+"_" 前缀，避免子串误判（如 mode "on" 误匹配 "offline"）
        for (int i = 0; i < smv.getModes().size(); i++) {
            String mode = smv.getModes().get(i);
            if (mode == null || mode.isEmpty()) continue;
            if (state.equals(mode) || state.startsWith(mode + "_")) {
                return i;
            }
        }
        // M3 修复：对分号分隔的 EndState（如 ";cool" 或 "auto;"），找到非空部分的索引
        if (state.contains(";")) {
            String[] parts = state.split(";", -1);
            for (int i = 0; i < parts.length && i < smv.getModes().size(); i++) {
                if (!parts[i].trim().isEmpty()) {
                    return i;
                }
            }
        }
        // 单值 fallback：检查 value 在哪个 mode 的状态列表中
        String cleanState = cleanStateName(state);
        for (int i = 0; i < smv.getModes().size(); i++) {
            List<String> modeStates = smv.getModeStates().get(smv.getModes().get(i));
            if (modeStates != null && modeStates.contains(cleanState)) {
                return i;
            }
        }
        return -1;
    }

    /*
     * Look up device SMV data by canonical varName.
     *
     * There were two public methods here, `findDeviceSmvData` and `findDeviceSmvDataStrict`, both delegating to
     * the same internal method with the same arguments — so there was no behavioural difference at all. Yet the
     * Strict javadoc claimed it "does not fall back to the template name": that fallback branch had already been
     * removed, so the comment described a distinction that no longer existed and callers had to choose between
     * two names for one behaviour. The non-strict name had no callers and is gone; this is the one the seven
     * real call sites use, with the pass-through internal layer folded in.
     */
    public static DeviceSmvData findDeviceSmvDataStrict(String deviceName,
                                                       Map<String, DeviceSmvData> deviceSmvMap) {
        if (deviceName == null || deviceSmvMap == null) return null;
        return deviceSmvMap.get(deviceName);
    }

    /** Resolve a declared mode name to its emitted NuSMV identifier. */
    public static String resolveModeIdentifier(DeviceSmvData smv, String modeName) {
        if (smv == null || smv.getModes() == null || modeName == null || modeName.isBlank()) return null;
        String authored = modeName.trim();
        if (!DeviceManifestModes.modeNames(smv.getManifest()).contains(authored)) return null;
        String identifier = sanitizeSmvToken(authored);
        return smv.getModes().contains(identifier) ? identifier : null;
    }

    /** 清理状态名：移除分号后进行 NuSMV 标识符清洗 */
    public static String cleanStateName(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replace(";", "").trim();
        if (cleaned.isEmpty()) return "";
        return sanitizeSmvToken(cleaned);
    }

    /** Empty and underscore tuple segments both mean that the mode is unconstrained. */
    public static boolean isWildcardStateSegment(String raw) {
        return raw == null || raw.trim().isEmpty() || "_".equals(raw.trim());
    }

    /** The emitted state for one positional API/transition endpoint, or null when omitted. */
    public static String getStateForMode(String tuple, int modeIndex, boolean startState) {
        if (tuple == null) return null;
        String[] states = tuple.split(";", -1);
        if (modeIndex >= states.length) return null;
        String raw = states[modeIndex].trim();
        if (raw.isEmpty() || (startState && isWildcardStateSegment(raw))) return null;
        return cleanStateName(raw);
    }

    /**
     * 清理 StartState：将空字符串和 "_" 视为通配符（任意状态）
     * 用于 API/Transition 的 StartState，表示"可从任意状态触发"
     */
    public static String cleanStartState(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replace(";", "").trim();
        if (cleaned.isEmpty() || cleaned.equals("_")) return "";
        return sanitizeSmvToken(cleaned);
    }

    /**
     * Resolves a command action to its template API.
     *
     * <p>Trims the requested name, because the two validators that admit a rule
     * ({@code NusmvRequestValidator} and {@code BoardStorageServiceImpl}) both compare trimmed.
     * An exact-only comparison here made this lookup stricter than admission: an action with a
     * trailing space passed validation and then resolved to nothing at generation time, silently
     * dropping the rule from the model. API names may legally contain interior spaces, so only the
     * surrounding whitespace is ignored.
     */
    public static DeviceManifest.API findApi(DeviceManifest manifest, String actionName) {
        if (manifest == null || manifest.getApis() == null || actionName == null) return null;
        String requested = actionName.trim();
        for (DeviceManifest.API api : manifest.getApis()) {
            // A null entry in Apis is malformed manifest data, not a match: skip it so generation
            // records a disabled rule instead of failing with an NPE. Matches the null guard in
            // BoardStorageServiceImpl.findApi and BoardSemanticValidator.findApi.
            if (api != null && requested.equals(api.getName())) return api;
        }
        return null;
    }

    /** 将任意设备 ID 转为安全变量名 */
    public static String toVarName(String deviceId) {
        String normalized = DeviceNameNormalizer.normalize(deviceId);
        return normalized == null || normalized.isBlank() ? "device_0" : normalized;
    }
}
