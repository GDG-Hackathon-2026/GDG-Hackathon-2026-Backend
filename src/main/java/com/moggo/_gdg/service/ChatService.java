package com.moggo._gdg.service;

import com.google.genai.types.Content;
import com.google.genai.types.Part;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@ConditionalOnExpression("'${gemini.project:}' != ''")
public class ChatService {

    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final GeminiService geminiService;
    private final CarbonPolicy carbonPolicy;
    private final PromptTemplateService promptTemplateService;

    public ChatService(UserRepository userRepository,
                       ConversationRepository conversationRepository,
                       MessageRepository messageRepository,
                       GeminiService geminiService,
                       CarbonPolicy carbonPolicy,
                       PromptTemplateService promptTemplateService) {
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.geminiService = geminiService;
        this.carbonPolicy = carbonPolicy;
        this.promptTemplateService = promptTemplateService;
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

        // 사용자 메시지는 원본 그대로 보존 (UI 상 잘려도 원문은 기록). 저장 후 이력에 포함됨.
        messageRepository.save(Message.userMessage(conv.getId(), content));

        // 이번 턴까지 포함한 모든 메시지 → 예산(max_input_tokens) 안에 들어오도록 과거부터 잘라냄.
        // 현재 user 턴은 반드시 포함, 이전 메시지 일부만 남는 "컨텍스트 창 축소" 가 stage 별로 실현된다.
        List<Message> allMessages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conv.getId());
        List<Content> contents = buildContentsWithinBudget(allMessages, state.maxInputTokens());
        boolean wasTruncated = contents.size() < allMessages.size();

        GeminiService.GenerationResult result = geminiService.generateWithHistory(
                contents,
                promptTemplateService.getActiveSystemPrompt()
        );

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

    /**
     * createdAt 오름차순 메시지 목록에서 예산(maxInputTokens) 이하가 되도록 오래된 것부터 버리고
     * Gemini 에 전달할 Content 배열을 구성한다.
     *
     * <p>토큰은 chars/4 근사. 가장 마지막(이번 턴의 user 메시지)은 반드시 포함하며, 예산을 혼자 초과하면
     * 해당 메시지 내부를 뒷부분 기준으로 잘라낸다. 앞의 이전 메시지들은 전체 단위로 drop.
     */
    private List<Content> buildContentsWithinBudget(List<Message> messagesAsc, int maxInputTokens) {
        int budgetChars = Math.max(1, maxInputTokens * 4);
        if (messagesAsc.isEmpty()) return List.of();

        // 뒤에서부터 쌓되 budget 초과 직전까지만 포함
        ArrayList<Message> kept = new ArrayList<>();
        int usedChars = 0;
        for (int i = messagesAsc.size() - 1; i >= 0; i--) {
            Message m = messagesAsc.get(i);
            int cost = m.getContent().length();
            if (kept.isEmpty()) {
                // 마지막 메시지(이번 user 턴)는 무조건 포함. 혼자 예산을 넘으면 뒷부분만 남긴다.
                if (cost > budgetChars) {
                    String clipped = m.getContent().substring(cost - budgetChars);
                    kept.add(cloneWithContent(m, clipped));
                    usedChars = budgetChars;
                } else {
                    kept.add(m);
                    usedChars = cost;
                }
                continue;
            }
            if (usedChars + cost > budgetChars) break;
            kept.add(m);
            usedChars += cost;
        }
        Collections.reverse(kept);

        List<Content> contents = new ArrayList<>(kept.size());
        for (Message m : kept) {
            String role = m.getRole() == Message.Role.USER ? "user" : "model";
            contents.add(Content.builder()
                    .role(role)
                    .parts(List.of(Part.fromText(m.getContent())))
                    .build());
        }
        return contents;
    }

    /** 예산 초과로 잘린 마지막 메시지를 임시 표현용으로 복제 (DB 변경 없음). */
    private Message cloneWithContent(Message original, String newContent) {
        if (original.getRole() == Message.Role.USER) {
            return Message.userMessage(original.getConversationId(), newContent);
        }
        // assistant 의 잘린 사본은 Gemini 에 보낼 이력 용도일 뿐이라 token/carbon 값은 0으로.
        return Message.assistantMessage(original.getConversationId(), newContent, 0, 0, 0.0);
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
