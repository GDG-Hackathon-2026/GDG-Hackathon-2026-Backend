package com.moggo._gdg.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "conversations", indexes = {
        @Index(name = "idx_conversations_user_uid", columnList = "user_uid")
})
@Getter
@NoArgsConstructor
@Schema(description = "하나의 사용자에 속하는 LLM 대화 세션")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "DB 자동 생성 ID. 메시지 전송/조회 시 path parameter 로 사용", example = "42")
    private Long id;

    @Column(name = "user_uid", length = 128, nullable = false)
    @Schema(description = "소유자 Firebase UID. 다른 사용자는 조회/수정 불가",
            example = "a1b2c3d4e5f6g7h8i9j0")
    private String userUid;

    @Setter
    @Column(length = 255)
    @Schema(description = "대화 제목. 생성 시 생략하면 'New conversation' 으로 기본 설정", example = "지구온난화 질문")
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Schema(description = "생성 시각 (ISO-8601, UTC)", example = "2026-04-24T08:30:00Z")
    private Instant createdAt;

    public Conversation(String userUid, String title) {
        this.userUid = userUid;
        this.title = title;
        this.createdAt = Instant.now();
    }
}
