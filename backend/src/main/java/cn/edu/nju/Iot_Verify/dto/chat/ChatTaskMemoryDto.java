package cn.edu.nju.Iot_Verify.dto.chat;

import cn.edu.nju.Iot_Verify.component.ai.TaskMemory;
import java.util.List;

public record ChatTaskMemoryDto(String status, long throughMessageId, boolean sceneChanged,
                                List<TaskMemory.Item> items) {}
