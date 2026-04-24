package com.moggo._gdg.domain;

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
public class Message {

    public enum Role { USER, ASSISTANT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "carbon_g")
    private Double carbonG;

    @Column(name = "created_at", nullable = false, updatable = false)
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
