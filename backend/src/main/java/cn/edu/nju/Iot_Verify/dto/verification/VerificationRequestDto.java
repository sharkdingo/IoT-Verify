package cn.edu.nju.Iot_Verify.dto.verification;

import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 验证请求
 *
 * 注意：Trace 会自动保存（当检测到违规时），无需前端传入 saveTrace 参数
 */
@Data
public class VerificationRequestDto {
    // The scene is NOT part of this request. The server reads devices, rules, specifications, the
    // environment pool, and the canvas layout from the caller's own persisted board, so a run always
    // describes the board the user saved. Accepting them here let an account with an empty board
    // post a fabricated scene and keep the resulting verdict in its run history. The fields below
    // are the frozen snapshot the service fills in from that board read; the strict request parser
    // rejects any attempt to supply them, so they are never client input.
    private List<DeviceVerificationDto> devices;

    /** Frozen canvas layout for faithful read-only replay; it is not part of NuSMV semantics. */
    private List<DeviceNodeDto> playbackNodes;

    /** Board-level environment pool, captured with the same board read as the devices. */
    private List<BoardEnvironmentVariableDto> environmentVariables;

    private List<RuleDto> rules = new ArrayList<>();

    private List<SpecificationDto> specs;

    /** Per-run attack selection. Trust labels remain independent board/model inputs. */
    @Valid
    @NotNull(message = "Attack scenario is required")
    private AttackScenarioDto attackScenario;

    /**
     * 是否启用隐私维度建模
     * 参考 MEDIC-test SMVGeneration.java 中的 now==3 标志
     *
     * 启用后会为每个设备状态/变量生成 privacy 标签变量，增加 NuSMV 状态空间。
     * privacy 条件存在时服务端会强制启用，以免把未建模的属性当成已验证。
     * This tracks sensitivity-label propagation; it does not implement access control or encryption.
     */
    private boolean enablePrivacy = false;

    @JsonIgnore
    public boolean isAttack() {
        return resolvedAttackScenario().isEnabled();
    }

    @JsonIgnore
    public int getAttackBudget() {
        return resolvedAttackScenario().effectiveBudget();
    }

    @JsonIgnore
    public AttackScenarioDto resolvedAttackScenario() {
        return Objects.requireNonNull(attackScenario, "attackScenario is required");
    }
}
