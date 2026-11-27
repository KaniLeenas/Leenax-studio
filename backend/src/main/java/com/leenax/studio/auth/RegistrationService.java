package com.leenax.studio.auth;

import com.leenax.studio.audit.AuditService;
import com.leenax.studio.auth.UserAdminDtos.RegistrationMode;
import com.leenax.studio.auth.UserAdminDtos.RegistrationModeRequest;
import com.leenax.studio.auth.UserAdminDtos.RegistrationSettings;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class RegistrationService {

    public static final String CHECK_EMAIL_MESSAGE = "If this address can register, a verification link will be sent.";

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AuthService auth;
    private final CurrentUser current;
    private final PasswordEncoder passwords;
    private final ActivationEmailSender email;
    private final AuditService audit;

    public RegistrationService(JdbcTemplate jdbc, UserRepository users, AuthService auth, CurrentUser current,
                               PasswordEncoder passwords, ActivationEmailSender email, AuditService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.auth = auth;
        this.current = current;
        this.passwords = passwords;
        this.email = email;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public boolean publicRegistrationEnabled() {
        return mode() == RegistrationMode.PUBLIC && email.isConfigured();
    }

    @Transactional(readOnly = true)
    public RegistrationSettings settings() {
        current.requireAdmin();
        return new RegistrationSettings(mode(), email.isConfigured());
    }

    @Transactional
    public RegistrationSettings updateMode(RegistrationModeRequest request) {
        User admin = current.requireAdmin();
        if (request.mode() == RegistrationMode.PUBLIC && !email.isConfigured()) {
            throw ApiException.badRequest("Configure outbound email before enabling public registration.");
        }
        jdbc.update("update app_settings set value = ?, updated_at = now() where key = 'registration_mode'", request.mode().name());
        audit.record("REGISTRATION_MODE_CHANGED", "settings", null, Map.of("mode", request.mode().name(), "by", admin.getId().toString()));
        return new RegistrationSettings(request.mode(), email.isConfigured());
    }

    @Transactional
    public RegistrationResult register(RegisterRequest request) {
        if (!publicRegistrationEnabled()) throw new ApiException(ErrorCode.FORBIDDEN, "Public registration is closed.");
        if (!request.password().equals(request.confirmPassword())) {
            throw ApiException.badRequest("The passwords do not match.");
        }
        PasswordPolicy.validate(request.password());
        String emailAddress = request.email().strip().toLowerCase(java.util.Locale.ROOT);
        if (users.findByEmailIgnoreCase(emailAddress).isPresent()) return new RegistrationResult(CHECK_EMAIL_MESSAGE);

        User user = User.invited(emailAddress, request.displayName().strip(), User.Role.MEMBER, true, null);
        user.setPasswordHash(passwords.encode(request.password()));
        user = users.saveAndFlush(user);
        AuthService.IssuedLink link = auth.issue(user, UserToken.Purpose.ACTIVATE, null);
        email.sendActivation(user.getEmail(), user.getDisplayName(), link.token());
        audit.record("PUBLIC_REGISTRATION_REQUESTED", "user", user.getId(), Map.of());
        return new RegistrationResult(CHECK_EMAIL_MESSAGE);
    }

    private RegistrationMode mode() {
        String value = jdbc.queryForObject("select value from app_settings where key = 'registration_mode'", String.class);
        try {
            return RegistrationMode.valueOf(value);
        } catch (RuntimeException e) {
            return RegistrationMode.INVITATION_ONLY;
        }
    }

    public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
                                  @NotBlank @Size(max = 80) String displayName,
                                  @NotBlank @Size(max = 200) String password,
                                  @NotBlank @Size(max = 200) String confirmPassword) {
        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + ", displayName=" + displayName + ", password=***]";
        }
    }

    public record RegistrationResult(String message) {
    }
}
