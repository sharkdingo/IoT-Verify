package cn.edu.nju.Iot_Verify.testutil;

import cn.edu.nju.Iot_Verify.component.ai.state.InMemoryAiSessionStateStore;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.component.aitool.scenario.AiScenarioDraftStore;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class AiSessionStateFixtures {

    private AiSessionStateFixtures() {
    }

    public static AiDestructiveActionGuard inMemoryActionGuard(ObjectMapper mapper) {
        return new AiDestructiveActionGuard(mapper, new InMemoryAiSessionStateStore());
    }

    public static AiScenarioDraftStore inMemoryScenarioDraftStore() {
        return new AiScenarioDraftStore(new InMemoryAiSessionStateStore(), new ObjectMapper());
    }
}
