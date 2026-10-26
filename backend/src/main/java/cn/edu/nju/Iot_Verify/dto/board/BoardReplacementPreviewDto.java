package cn.edu.nju.Iot_Verify.dto.board;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;

import java.util.List;

/** Authoritative current-board impact shown before an explicit full-scene replacement. */
@Data
@Builder
public class BoardReplacementPreviewDto {
    @ToString.Exclude
    private String impactToken;
    private int deviceCount;
    private int environmentVariableCount;
    private int ruleCount;
    private int specificationCount;
    private int editHistoryEntryCount;
    private List<TemplateUsage> templates;

    public record TemplateUsage(String name, boolean defaultTemplate, int deviceCount) {}
}
