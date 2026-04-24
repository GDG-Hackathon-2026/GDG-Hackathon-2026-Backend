package com.moggo._gdg.service;

import com.moggo._gdg.domain.Conversation;
import com.moggo._gdg.domain.Message;
import com.moggo._gdg.domain.User;
import com.moggo._gdg.repository.ConversationRepository;
import com.moggo._gdg.repository.MessageRepository;
import com.moggo._gdg.repository.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@ConditionalOnExpression("'${gemini.project:}' != ''")
public class ChatService {

    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final GeminiService geminiService;
    private final CarbonPolicy carbonPolicy;

    public ChatService(UserRepository userRepository,
                       ConversationRepository conversationRepository,
                       MessageRepository messageRepository,
                       GeminiService geminiService,
                       CarbonPolicy carbonPolicy) {
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.geminiService = geminiService;
        this.carbonPolicy = carbonPolicy;
    }

    @Transactional
    public Conversation createConversation(String uid, String title) {
        userRepository.findById(uid).orElseGet(() -> userRepository.save(new User(uid)));
        String t = (title == null || title.isBlank()) ? "New conversation" : title;
        return conversationRepository.save(new Conversation(uid, t));
    }

    @Transactional(readOnly = true)
    public List<Conversation> listConversations(String uid) {
        return conversationRepository.findByUserUidOrderByCreatedAtDesc(uid);
    }

    @Transactional(readOnly = true)
    public ConversationView getConversation(String uid, Long id) {
        Conversation conv = conversationRepository.findByIdAndUserUid(id, uid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found"));
        List<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conv.getId());
        return new ConversationView(conv, messages);
    }

    @Transactional
    public SendResult sendMessage(String uid, Long conversationId, String content) {
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must not be blank");
        }
        User user = userRepository.findById(uid).orElseGet(() -> userRepository.save(new User(uid)));

        CarbonPolicy.MeltingState state = carbonPolicy.meltingStateFor(user.getCarbonUsedG());
        if (state.isRejected()) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "melted: carbon budget exhausted, no further LLM calls allowed");
        }

        Conversation conv = conversationRepository.findByIdAndUserUid(conversationId, uid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found"));

        // 사용자 메시지는 원본 그대로 보존 (UI 상 잘려도 원문은 기록)
        messageRepository.save(Message.userMessage(conv.getId(), content));

        // Gemini 호출에 쓸 prompt 는 max_input_tokens 로 잘라서 전송 (토큰은 chars/4 근사)
        String truncated = truncateToApproxTokens(content, state.maxInputTokens());
        boolean wasTruncated = !truncated.equals(content);

        GeminiService.GenerationResult result = geminiService.generateWithUsage(truncated, state.maxInputTokens());

        double carbon = carbonPolicy.estimate(result.promptTokens(), result.completionTokens());
        user.addCarbon(carbon);
        userRepository.save(user);

        Message assistant = messageRepository.save(
                Message.assistantMessage(conv.getId(), result.text(),
                        result.promptTokens(), result.completionTokens(), carbon));

        CarbonPolicy.MeltingState newState = carbonPolicy.meltingStateFor(user.getCarbonUsedG());
        int percent = carbonPolicy.meltingPercent(user.getCarbonUsedG());

        return new SendResult(
                new AssistantMessageView(
                        assistant.getId(),
                        result.text(),
                        result.promptTokens(),
                        result.completionTokens(),
                        carbon),
                new CarbonState(user.getCarbonUsedG(), newState.stage(), newState.maxInputTokens(), percent),
                wasTruncated
        );
    }

    private String truncateToApproxTokens(String text, int maxTokens) {
        int maxChars = Math.max(1, maxTokens * 4);
        if (text.length() <= maxChars) return text;
        // 앞부분을 버리고 뒷부분만 남김 (대화 맥락에서 최근 내용이 중요)
        return text.substring(text.length() - maxChars);
    }

    public record ConversationView(Conversation conversation, List<Message> messages) {}

    public record AssistantMessageView(
            Long id,
            String content,
            int promptTokens,
            int completionTokens,
            double carbonG
    ) {}

    public record CarbonState(double totalCarbonG, int stage, int maxInputTokens, int meltingPercent) {}

    public record SendResult(AssistantMessageView assistantMessage, CarbonState carbonState, boolean truncated) {}
}
