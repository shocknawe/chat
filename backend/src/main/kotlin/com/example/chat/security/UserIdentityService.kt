package com.example.chat.security

import com.example.chat.repository.AppUserRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Resolves a raw, client-supplied user id string against the set of seeded
 * MVP users (design.md: "Window-scoped demo identity with minimal Spring
 * Security binding").
 *
 * This is the single reusable "is this a valid seeded user" check, shared by:
 *  - [UserIdHeaderAuthenticationFilter], which validates the REST `X-User-Id`
 *    header (this work package, WP3).
 *  - A WebSocket handshake interceptor (a later work package, WP4), which
 *    will validate the same non-secret id carried as the `?userId=` query
 *    parameter, since the browser WebSocket API cannot set custom headers
 *    (design.md).
 *
 * Deliberately spoofable: this is demo identity *selection*, not production
 * authentication, per design.md's explicit trade-off — the value is never a
 * credential.
 */
@Component
class UserIdentityService(
    private val appUserRepository: AppUserRepository,
) {

    /**
     * Returns the [AuthenticatedUser] for [rawUserId] if it is a syntactically
     * valid UUID belonging to a seeded [com.example.chat.domain.AppUser], or
     * `null` if [rawUserId] is missing, blank, malformed, or does not match
     * any seeded user.
     *
     * Callers that need to distinguish "no/invalid identity" (401) from
     * "valid identity, insufficient permission" (403) should treat a `null`
     * return here as the former; the latter is a separate, later check
     * (e.g. conversation participation) performed once an [AuthenticatedUser]
     * has already been resolved.
     */
    fun resolve(rawUserId: String?): AuthenticatedUser? {
        if (rawUserId.isNullOrBlank()) return null
        val id = runCatching { UUID.fromString(rawUserId.trim()) }.getOrNull() ?: return null
        val appUser = appUserRepository.findById(id).orElse(null) ?: return null
        return AuthenticatedUser(id = appUser.id, displayName = appUser.displayName)
    }
}
