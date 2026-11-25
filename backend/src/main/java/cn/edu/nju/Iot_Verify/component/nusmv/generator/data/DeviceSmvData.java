package cn.edu.nju.Iot_Verify.component.nusmv.generator.data;

import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import lombok.Data;

import java.util.*;

/**
 * 设备 SMV 数据模型（纯数据，无行为）
 *
 * 承载从用户输入 + 设备模板解析出的所有信息，供各 SMV 模块构建器消费。
 * 由 DeviceSmvDataFactory 构建并填充所有字段。
 */
@Data
public class DeviceSmvData {

    // ── 设备标识 ──
    private String templateName;   // 模板名称（如 "Light"）
    private String deviceLabel;    // 用户可见实例名称；仅用于结果展示
    private ModelTokenSource modelTokenSource = ModelTokenSource.UNKNOWN;

    // ── NuSMV 标识符（由 Factory 计算填充） ──
    private String moduleName;    // MODULE 名称（如 "Light_light1"）
    private String varName;       // 实例变量名（如 "light_1"），同时作为 deviceSmvMap 的 key
    private boolean sensor;       // 无控制 API 的传感器型设备；复合设备的外部读数仍单独建模攻击影响

    // ── 模式与状态（从模板 Modes + WorkingStates 解析） ──
    private List<String> modes = new ArrayList<>();
    /** Inverse mapping for mode identifiers whose spelling changed during compilation. */
    private final Map<String, String> renamedModes = new HashMap<>();
    private Map<String, Map<String, String>> authoredStatesByMode = Map.of();
    private Map<String, List<String>> modeStates = new LinkedHashMap<>();
    private List<String> states = new ArrayList<>();

    // ── 变量（从模板 InternalVariables 解析） ──
    private List<DeviceManifest.InternalVariable> variables = new ArrayList<>();
    private Map<String, DeviceManifest.InternalVariable> envVariables = new HashMap<>();
    private List<String> impactedVariables = new ArrayList<>();
    private Map<String, DeviceManifest.InternalVariable> impactedEnvironmentVariables = new HashMap<>();

    /**
     * Every shared declaration this template makes, read or affect-only, keyed by name.
     *
     * <p>Separate from {@code envVariables}, which is narrower: that map is the rule/spec
     * source-capability set and therefore excludes affect-only declarations. Domain resolution needs
     * all of them, capability needs only the readable ones, and conflating the two is what forced a
     * second manifest array to exist.
     */
    private Map<String, DeviceManifest.InternalVariable> sharedDeclarations = new HashMap<>();

    // ── API event signal variables (APIs[Signal=true]) ──
    private List<SignalInfo> signalVars = new ArrayList<>();

    // ── 内容（从模板 Contents 解析，如 Mobile Phone 的 photo） ──
    private List<ContentInfo> contents = new ArrayList<>();

    // ── 用户运行时输入 ──
    private String currentState;
    private Map<String, String> currentModeStates = new LinkedHashMap<>();
    private Map<String, String> variableValues = new HashMap<>();

    // ── 模板默认初始状态（从 manifest.InitState 解析） ──
    private Map<String, String> templateInitModeStates = new LinkedHashMap<>();

    // ── 信任/隐私（模板默认 + 用户实例覆盖） ──
    private Map<String, String> modeStateTrust = new LinkedHashMap<>();
    private String instanceStateTrust;
    private String instanceStatePrivacy;
    private Map<String, String> instanceVariableTrust = new HashMap<>();
    private Map<String, String> instanceVariablePrivacy = new HashMap<>();

    // ── 模板引用 ──
    private transient DeviceManifest manifest;

    public String authoredModeName(String identifier) {
        return renamedModes.getOrDefault(identifier, identifier);
    }

    public String authoredStateName(String mode, String state) {
        return authoredStatesByMode.getOrDefault(authoredModeName(mode), Map.of()).getOrDefault(state, state);
    }

    public String resolveAuthoredState(String mode, String name) {
        if (name == null || name.isBlank()) return null;
        String authored = name.trim();
        String token = DeviceSmvDataFactory.cleanStateName(authored);
        return modeStates.getOrDefault(mode, List.of()).contains(token) && authored.equals(authoredStateName(mode, token))
                ? token : null;
    }

    // ── 内部数据类 ──

    @Data
    public static class SignalInfo {
        private String name;
    }

    @Data
    public static class ContentInfo {
        private String name;       // content 名称（如 "photo"）
        private String privacy;    // 初始隐私（如 "private"）
    }
}
