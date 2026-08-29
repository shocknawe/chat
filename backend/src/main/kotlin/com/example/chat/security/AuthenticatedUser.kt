package com.example.chat.security

import java.util.UUID

/**
 * The Spring Security `Authentication` principal for a validated demo
 * identity (design.md: "Window-scoped demo identity with minimal Spring
 * Security binding").
 *
 * Deliberately a small, detached value — not the [com.example.chat.domain.AppUser]
 * JPA entity itself — so the security context never holds a
 * persistence-context-bound object across request/thread boundaries.
 *
 * Controllers obtain this via `@AuthenticationPrincipal currentUser: AuthenticatedUser`.
 */
data class AuthenticatedUser(
    val id: UUID,
    val displayName: String,
)
