package cn.edu.nju.Iot_Verify.dto.board;

import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of undoing or redoing one board edit.
 *
 * <p>Carries the authoritative post-operation device, Environment Pool, rule, and specification
 * lists so the client replaces its local collections outright rather than trying to invert the
 * edit itself, plus the remaining availability so the UI never has to infer it from a local stack.
 *
 * <p>{@code applied} is false when the reviewed history has no entry in the requested direction.
 * Requests for a superseded history are rejected before any entry can be selected.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BoardUndoResultDto {

    private boolean applied;

    /** Journal entity type; absent when nothing was applied or history was only queried/cleared. */
    private String entityType;

    /** CREATE, UPDATE, or DELETE: what the *original* edit did, not what the undo did. */
    private String originalOperation;

    /** Short stable code naming what the undo did, for client wording. */
    private String reasonCode;

    private List<DeviceNodeDto> nodes;
    private List<BoardEnvironmentVariableDto> environmentVariables;
    private List<RuleDto> rules;
    private List<SpecificationDto> specs;

    private boolean canUndo;
    private boolean canRedo;
    private String editHistoryRevision;
}
