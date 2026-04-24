package com.moggo._gdg.domain;

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
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_uid", length = 128, nullable = false)
    private String userUid;

    @Setter
    @Column(length = 255)
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Conversation(String userUid, String title) {
        this.userUid = userUid;
        this.title = title;
        this.createdAt = Instant.now();
    }
}
