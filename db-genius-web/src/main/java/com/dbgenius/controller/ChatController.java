package com.dbgenius.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.dbgenius.common.result.R;
import com.dbgenius.intent.IntentRouter;
import com.dbgenius.model.dto.UnifiedChatRequest;
import com.dbgenius.model.entity.Message;
import com.dbgenius.model.vo.ConversationVO;
import com.dbgenius.service.ConversationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final IntentRouter intentRouter;
    private final ConversationService conversationService;

    /**
     * 统一对话入口（SSE 流式输出）
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody UnifiedChatRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return intentRouter.route(request, userId);
    }

    @GetMapping("/conversations")
    public R<List<ConversationVO>> listConversations() {
        return R.ok(conversationService.listConversations(StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/conversations/{id}/messages")
    public R<List<Message>> getMessages(@PathVariable Long id) {
        return R.ok(conversationService.getMessages(id));
    }

    @DeleteMapping("/conversations/{id}")
    public R<Void> deleteConversation(@PathVariable Long id) {
        conversationService.deleteConversation(StpUtil.getLoginIdAsLong(), id);
        return R.ok();
    }
}
