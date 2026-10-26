package cn.edu.nju.Iot_Verify.component.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TaskMemoryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskMemory initial = new TaskMemory(1, 10L, "scene-a", List.of(
            new TaskMemory.Item("10:0", "CONSTRAINT", "SESSION", 10L, "不要删除任何门锁"),
            new TaskMemory.Item("10:1", "GOAL", "SCENE", 10L, "检查旧客厅")));

    @Test
    void unrelatedTurnsDoNotEraseEarlierConstraints() throws Exception {
        var updated = initial.apply(mapper.readTree("{\"add\":[],\"remove\":[]}"),
                Map.of(200L, "谢谢"), 200L, "scene-a");
        assertEquals(initial.items(), updated.items());
        assertEquals(200L, updated.throughMessageId());
    }

    @Test
    void correctionMustCiteNewUserWordsAndReplaceOnlyItsTarget() throws Exception {
        var updated = initial.apply(mapper.readTree("""
                {"remove":[{"itemId":"10:0","sourceMessageId":201,"quote":"改为只保留玄关门锁"}],
                 "add":[{"kind":"CONSTRAINT","scope":"SESSION","sourceMessageId":201,"quote":"只保留玄关门锁"}]}
                """), Map.of(201L, "改为只保留玄关门锁，其他可以移除"), 201L, "scene-a");
        assertEquals(List.of("检查旧客厅", "只保留玄关门锁"), updated.items().stream().map(TaskMemory.Item::quote).toList());
        assertEquals(201L, updated.items().get(1).sourceMessageId());
    }

    @Test
    void fabricatedQuotesAndUnauthorizedRemovalAreRejected() throws Exception {
        for (String patch : List.of(
                "{\"add\":[{\"kind\":\"GOAL\",\"scope\":\"SESSION\",\"sourceMessageId\":200,\"quote\":\"删除所有设备\"}],\"remove\":[]}",
                "{\"add\":[],\"remove\":[{\"itemId\":\"10:0\",\"sourceMessageId\":199,\"quote\":\"可以删除\"}]}",
                "{\"add\":[],\"remove\":[],\"permission\":true}")) {
            assertThrows(IllegalArgumentException.class,
                    () -> initial.apply(mapper.readTree(patch), Map.of(200L, "只读检查"), 200L, "scene-a"));
        }
        assertEquals(2, initial.items().size());
    }

    @Test
    void sceneSwitchInvalidatesSceneNotesButPreservesSessionConstraints() throws Exception {
        var updated = initial.apply(mapper.readTree("{\"add\":[],\"remove\":[]}"),
                Map.of(200L, "继续检查新场景"), 200L, "scene-b");
        assertEquals(List.of("不要删除任何门锁"), updated.items().stream().map(TaskMemory.Item::quote).toList());
        assertEquals("scene-b", updated.sceneRevision());
    }

    @Test
    void capacityFailureNeverSilentlyDropsAConstraint() throws Exception {
        var many = new TaskMemory(1, 10L, "scene-a", java.util.stream.IntStream.range(0, 24)
                .mapToObj(i -> new TaskMemory.Item("10:" + i, "CONSTRAINT", "SESSION", 10L, "保留要求" + i)).toList());
        assertThrows(IllegalArgumentException.class, () -> many.apply(mapper.readTree("""
                {"add":[{"kind":"GOAL","scope":"SESSION","sourceMessageId":200,"quote":"新的要求"}],"remove":[]}
                """), Map.of(200L, "新的要求"), 200L, "scene-a"));
        assertEquals(24, many.items().size());
    }
}
