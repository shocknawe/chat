package com.example.chat.repository

import com.example.chat.domain.AppUser
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AppUserRepository : JpaRepository<AppUser, UUID>
