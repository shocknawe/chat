package com.example.chat.repository

import com.example.chat.domain.Conversation
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ConversationRepository : JpaRepository<Conversation, UUID>
