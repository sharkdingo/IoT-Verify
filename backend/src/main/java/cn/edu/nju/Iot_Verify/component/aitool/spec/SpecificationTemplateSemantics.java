package cn.edu.nju.Iot_Verify.component.aitool.spec;

/** Single model-facing authority for IoT-Verify specification template semantics. */
public final class SpecificationTemplateSemantics {

    private SpecificationTemplateSemantics() {
    }

    public static String chinesePromptReference() {
        return """
                1. Always: AG(A)。仅使用 aConditions；A 的合取在所有路径的所有状态都必须成立，不是 IF/THEN 条件句。
                2. Eventually: AF(A)。仅使用 aConditions；从初始状态出发，A 在所有路径上最终成立，不是 EF，也没有前置触发条件。
                3. Never: AG !(A)。仅使用 aConditions；A 的合取在所有建模路径中永不发生。
                4. Immediate: AG((IF) -> AX(THEN))。仅使用 ifConditions/thenConditions；IF 成立后的下一状态满足 THEN。
                5. Response: AG((IF) -> AF(THEN))。仅使用 ifConditions/thenConditions；IF 成立后，THEN 在所有后续路径上最终成立。
                6. Persistence: G((IF) -> F G(THEN))。仅使用 ifConditions/thenConditions；IF 成立后，THEN 最终开始持续成立。
                7. Untrusted-source safety: AG !(全部 A 条件 & (任一条件解析出的 untrusted 来源标签))。仅使用 aConditions；A 条件之间取合取，而来源标签在多个条件之间取析取，因此只要其中一个来源不可信即视为违规。它保护事件不被不可信来源触发，不等同于普通 Never。
                变量条件的值来源必须匹配用户要检查的问题：
                - 用户说设备“报告、上报、读数、测量或检测到的值”时，使用 variableSource=reported；即使变量存放在共享环境中，也是在检查这个设备报告了什么。
                - 用户明确检查“家中实际值、真实环境值或物理环境”时，才使用 variableSource=environment，并遵守共享且 reads=true 的能力约束。
                - environmentVariables 的初值只定义场景起点，不会把用户要求的设备报告值变成真实环境值。两者在无攻击时可能相等，受到干扰时却是不同的问题，不能为简化建模或取得通过而替换。
                - 输出理由必须说明实际选取的来源；来源不明确时保留该疑问，不把一种来源的条件解释为另一种。
                """;
    }
}
