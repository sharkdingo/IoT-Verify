package cn.edu.nju.Iot_Verify.dto.board;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Authoritative result of a field-level Environment Pool patch operation. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnvironmentMutationResultDto {

    private String operation;
    @Builder.Default
    private List<EnvironmentVariablePatchResultDto> patchResults = List.of();
    @Builder.Default
    private List<BoardEnvironmentVariableDto> environmentVariables = List.of();
    @Builder.Default
    private List<EnvironmentVariableChangeDto> environmentChanges = List.of();
    private int currentCount;
    /** Present only when an actual Environment Pool update was committed and journalled. */
    private Boolean canUndo;
    /** Present only when an actual Environment Pool update was committed and journalled. */
    private Boolean canRedo;
    private String editHistoryRevision;
}
