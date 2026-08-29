package com.example.chat.api

import com.example.chat.api.dto.UserDto
import com.example.chat.repository.AppUserRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** user-directory capability: `GET /api/users` is public (design.md). */
@RestController
@RequestMapping("/api/users")
class UserController(
    private val appUserRepository: AppUserRepository,
) {

    /**
     * Returns the configured MVP users so the frontend can present
     * selectable identities before any current user is established
     * (user-directory spec: "Available users are exposed to clients").
     */
    @GetMapping
    fun listUsers(): List<UserDto> =
        appUserRepository.findAll()
            .map { UserDto(id = it.id, displayName = it.displayName) }
            .sortedBy { it.displayName }
}
