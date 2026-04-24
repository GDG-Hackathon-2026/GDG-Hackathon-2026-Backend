package com.moggo._gdg.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "messages", indexes = {
        @Index(name = "idx_messages_conversation_id", columnList = "conversation_id")
})
@Getter
@NoArgsConstructor
@Schema(description = "사용자 또는 어시스턴트(Gemini) 단일 메시지")
public class Message {

    public enum Role {
        @Schema(description = "사용자가 보낸 메시지") USER,
        @Schema(description = "Gemini 응답 메시지") ASSISTANT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "DB 자동 생성 ID", example = "1234")
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    @Schema(description = "소속 대화 ID", example = "42")
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Schema(description = "메시지 발신자")
    private Role role;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    @Schema(description = "메시지 본문. user 메시지는 원본 보존, assistant 는 Gemini 응답 텍스트",
            example = "지구온난화는 주로 온실가스 배출 증가로 인한 현상입니다.")
    private String content;

    @Column(name = "prompt_tokens")
    @Schema(description = "(assistant 전용) Gemini 가 받은 프롬프트 토큰 수",
            example = "42", nullable = true)
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    @Schema(description = "(assistant 전용) Gemini 가 생성한 응답 토큰 수",
            example = "128", nullable = true)
    private Integer completionTokens;

    @Column(name = "carbon_g")
    @Schema(description = "(assistant 전용) 이 응답으로 누적된 탄소 배출량 (gCO₂eq). "
            + "`(promptTokens/1000)*g_per_1k_input + (completionTokens/1000)*g_per_1k_output`",
            example = "0.0038", nullable = true)
    private Double carbonG;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Schema(description = "생성 시각 (ISO-8601, UTC)", example = "2026-04-24T08:31:12Z")
    private Instant createdAt;

    private Message(Long conversationId, Role role, String content) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public static Message userMessage(Long conversationId, String content) {
        return new Message(conversationId, Role.USER, content);
    }

    public static Message assistantMessage(Long conversationId, String content,
                                           int promptTokens, int completionTokens, double carbonG) {
        Message m = new Message(conversationId, Role.ASSISTANT, content);
        m.promptTokens = promptTokens;
        m.completionTokens = completionTokens;
        m.carbonG = carbonG;
        return m;
    }
}
