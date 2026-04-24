package com.moggo._gdg.repository;

import com.moggo._gdg.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findByUserUidOrderByCreatedAtDesc(String userUid);

    Optional<Conversation> findByIdAndUserUid(Long id, String userUid);
}
