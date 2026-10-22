package cn.edu.nju.Iot_Verify.component.nusmv.generator;

import cn.edu.nju.Iot_Verify.component.nusmv.SpecResultAlignment;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/** Lets scene assertions count user properties while still requiring every auxiliary verdict. */
final class FormalCheckTestSupport {
    private FormalCheckTestSupport() {}

    static NusmvExecutor.NusmvResult checkProperties(NusmvExecutor executor, SmvGenerator.GenerateResult model)
            throws InterruptedException {
        var result = executor.execute(model.smvFile());
        assertTrue(result.isSuccess(), result::getErrorMessage);
        var alignment = SpecResultAlignment.align(result.getSpecResults(), model.emittedSpecs());
        assertEquals(alignment.expectedResultCount(), result.getSpecResults().size());
        assertEquals(0, alignment.backFilled());
        assertTrue(alignment.aligned().stream().noneMatch(Objects::isNull));
        for (int i = 0; i < model.emittedSpecs().size(); i++) {
            if (model.emittedSpecs().get(i).antecedentExpression() != null) assertNotNull(alignment.antecedents().get(i));
        }
        return NusmvExecutor.NusmvResult.success(result.getOutput(), alignment.aligned());
    }
}
