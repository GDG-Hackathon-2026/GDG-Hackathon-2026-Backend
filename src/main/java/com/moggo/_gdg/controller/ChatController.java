package com.moggo._gdg.controller;

import com.moggo._gdg.domain.Conversation;
import com.moggo._gdg.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
@ConditionalOnExpression("'${gemini.project:}' != ''")
@Tag(name = "Chat", description = "대화 및 메시지 (Gemini 연동 + 탄소 누적 + 녹아내림 단계 반영)")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    @Operation(summary = "Create a new conversation")
    public Conversation create(@AuthenticationPrincipal String uid,
                               @RequestBody(required = false) CreateRequest request) {
        String title = request == null ? null : request.title();
        return chatService.createConversation(uid, title);
    }

    @GetMapping
    @Operation(summary = "List my conversations")
    public List<Conversation> list(@AuthenticationPrincipal String uid) {
        return chatService.listConversations(uid);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a conversation with all messages")
    public ChatService.ConversationView get(@AuthenticationPrincipal String uid,
                                            @PathVariable Long id) {
        return chatService.getConversation(uid, id);
    }

    @PostMapping("/{id}/messages")
    @Operation(summary = "Send a message and get Gemini response",
            description = "요청은 max_input_tokens 기반으로 서버에서 잘릴 수 있음. " +
                    "탄소 누적 500g 이상이면 402 Payment Required 로 거부.")
    public ChatService.SendResult send(@AuthenticationPrincipal String uid,
                                       @PathVariable Long id,
                                       @RequestBody SendRequest request) {
        return chatService.sendMessage(uid, id, request.content());
    }

    public record CreateRequest(String title) {}

    public record SendRequest(String content) {}
}
