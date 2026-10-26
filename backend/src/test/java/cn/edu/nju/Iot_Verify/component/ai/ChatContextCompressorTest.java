package cn.edu.nju.Iot_Verify.component.ai;

import cn.edu.nju.Iot_Verify.component.ai.chat.ChatToolProgressPresenter;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmMessage;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChatContextCompressorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatContextCompressor compressor = new ChatContextCompressor(mapper, new ChatToolProgressPresenter(mapper));

    @Test void compactsOldPayloadsWithoutBreakingCallsOrLosingLatestEvidenceAndHumanCorrection() {
        String original = "{\"error\":\"" + "offline".repeat(2000) + "\",\"errorCode\":\"FAILED\",\"status\":503}";
        String latest = "{\"specifications\":[],\"count\":0,\"detail\":\"" + "latest evidence".repeat(500) + "\"}";
        var input = new ArrayList<>(List.of(LlmMessage.system("Task memory: preserve locks"),
                LlmMessage.user("改口：现在只保留玄关门锁"),
                LlmMessage.assistantToolCalls(List.of(new LlmToolCall("a", "list_rules", "{}"))),
                LlmMessage.tool("a", original),
                LlmMessage.assistantToolCalls(List.of(new LlmToolCall("b", "list_specs", "{}"))),
                LlmMessage.tool("b", latest)));
        compressor.compact(input, 4000);
        assertEquals(6, input.size());
        assertTrue(input.get(3).content().contains("contextCompacted"));
        assertTrue(input.get(3).content().length() < 1000);
        assertEquals("a", input.get(3).toolCallId());
        assertEquals("改口：现在只保留玄关门锁", input.get(1).content());
        assertEquals(latest, input.get(5).content());
        assertFalse(input.get(3).content().contains("appliedAction"));
        var once = List.copyOf(input);
        compressor.compact(input, 100);
        assertEquals(once, input);
    }

    @Test void leavesShortConversationsAndOriginalMessageObjectsUntouched() {
        var original = LlmMessage.tool("a", "{\"count\":0}");
        var input = new ArrayList<>(List.of(original, LlmMessage.user("继续")));
        compressor.compact(input, 4000);
        assertSame(original, input.get(0));
    }
}
