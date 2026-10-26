package cn.edu.nju.Iot_Verify.controller;

import cn.edu.nju.Iot_Verify.dto.Result;
import cn.edu.nju.Iot_Verify.dto.chat.ChatTaskMemoryDto;
import cn.edu.nju.Iot_Verify.security.CurrentUser;
import cn.edu.nju.Iot_Verify.service.AiTaskMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ChatTaskMemoryController {
    private final AiTaskMemoryService memory;

    @GetMapping("/api/chat/sessions/{sessionId}/memory")
    public Result<ChatTaskMemoryDto> read(@CurrentUser Long userId, @PathVariable String sessionId) {
        return Result.success(memory.read(userId, sessionId));
    }
}
