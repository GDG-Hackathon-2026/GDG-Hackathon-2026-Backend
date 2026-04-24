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

    @io.swagger.v3.oas.annotations.media.Schema(
            description = "대화 상세 조회 응답 — 대화 메타데이터 + 시간순 정렬된 전체 메시지 목록")
    public record ConversationView(
            @io.swagger.v3.oas.annotations.media.Schema(description = "대화 메타데이터")
            Conversation conversation,
            @io.swagger.v3.oas.annotations.media.Schema(description = "createdAt 오름차순. 사용자/어시스턴트 교대로 등장")
            List<Message> messages
    ) {}

    @io.swagger.v3.oas.annotations.media.Schema(description = "Gemini 응답 메시지 (sendMessage 응답의 일부)")
    public record AssistantMessageView(
            @io.swagger.v3.oas.annotations.media.Schema(description = "DB 에 저장된 메시지 ID", example = "1235")
            Long id,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Gemini 응답 텍스트",
                    example = "지구온난화는 주로 온실가스 배출로 인한…")
            String content,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Gemini usageMetadata 의 promptTokenCount",
                    example = "42")
            int promptTokens,
            @io.swagger.v3.oas.annotations.media.Schema(description = "Gemini usageMetadata 의 candidatesTokenCount",
                    example = "128")
            int completionTokens,
            @io.swagger.v3.oas.annotations.media.Schema(description = "이 응답 한 건으로 발생한 탄소 (gCO₂eq)",
                    example = "0.0038")
            double carbonG
    ) {}

    @io.swagger.v3.oas.annotations.media.Schema(description = "메시지 전송 후 갱신된 탄소/녹아내림 상태")
    public record CarbonState(
            @io.swagger.v3.oas.annotations.media.Schema(description = "갱신된 누적 탄소", example = "12.838")
            double totalCarbonG,
            @io.swagger.v3.oas.annotations.media.Schema(description = "새 단계 (0~5)", example = "2")
            int stage,
            @io.swagger.v3.oas.annotations.media.Schema(description = "다음 호출에 적용될 허용 input tokens",
                    example = "2048")
            int maxInputTokens,
            @io.swagger.v3.oas.annotations.media.Schema(description = "현재 stage 진행률 (0~100)", example = "42")
            int meltingPercent
    ) {}

    @io.swagger.v3.oas.annotations.media.Schema(description = "메시지 전송 응답")
    public record SendResult(
            @io.swagger.v3.oas.annotations.media.Schema(description = "어시스턴트 응답 + 사용량 + 탄소")
            AssistantMessageView assistantMessage,
            @io.swagger.v3.oas.annotations.media.Schema(description = "갱신된 사용자 탄소 상태 (다음 요청부터 적용)")
            CarbonState carbonState,
            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "입력 prompt 가 maxInputTokens 를 초과해 서버에서 앞부분이 잘렸는지 여부. "
                            + "프론트에서 'N 글자가 생략되었습니다' 같은 안내 표시에 사용",
                    example = "false")
            boolean truncated
    ) {}
}
