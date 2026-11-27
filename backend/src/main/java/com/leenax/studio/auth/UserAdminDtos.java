package com.leenax.studio.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class UserAdminDtos {

    private UserAdminDtos() {
    }

    public record InviteRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 80) String displayName,
            @NotNull User.Role role,
            boolean canCreateProjects) {
    }

    public record UpdateRequest(
            @NotBlank @Size(max = 80) String displayName,
            @NotNull User.Role role,
            boolean canCreateProjects) {
    }

    public record Person(UUID id, String email, String displayName, User.Role role, User.Status status,
                         boolean canCreateProjects, boolean mfaEnabled, Instant lastLoginAt, Instant activatedAt,
                         Instant deactivatedAt, Instant createdAt) {
        static Person of(User u) {
            return new Person(u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(), u.getStatus(),
                    u.canCreateProjects(), u.isMfaEnabled(), u.getLastLoginAt(), u.getActivatedAt(),
                    u.getDeactivatedAt(), u.getCreatedAt());
        }
    }

    public record UsersResponse(List<Person> users, int page, int size, long totalItems, int totalPages) {
    }

    /** A one-time link, shown once: the administrator copies it to the person. Only its hash is stored. */
    public record LinkResponse(Person user, String path, Instant expiresAt) {
    }

    /** What every signed-in person may see about colleagues, to add them to projects. */
    public record Colleague(UUID id, String displayName, String email) {
    }

    public record LoginResponse(UUID id, UUID userId, String email, boolean success, String reason,
                                String ipAddress, String userAgent, Instant createdAt) {
        static LoginResponse of(LoginEvent e) {
            return new LoginResponse(e.getId(), e.getUserId(), e.getEmail(), e.isSuccess(), e.getReason(),
                    e.getIpAddress(), e.getUserAgent(), e.getCreatedAt());
        }
    }

    public enum RegistrationMode { INVITATION_ONLY, PUBLIC }

    public record RegistrationSettings(RegistrationMode mode, boolean emailConfigured) {
    }

    public record RegistrationModeRequest(@NotNull RegistrationMode mode) {
    }
}
