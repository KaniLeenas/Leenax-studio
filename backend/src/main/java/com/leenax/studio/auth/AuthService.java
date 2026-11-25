package com.leenax.studio.auth;

import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sign-in accounts: the first administrator (setup), each person's own profile and
 * password, and the one-time links that activate an account or reset a password.
 */
@Service
public class AuthService {

    /** An activation link lasts a week; a reset link a day. */
    static final Duration ACTIVATE_FOR = Duration.ofDays(7);
    static final Duration RESET_FOR = Duration.ofHours(24);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final UserTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final AuditService audit;

    public AuthService(UserRepository users, UserTokenRepository tokens, PasswordEncoder passwordEncoder,
                       AuthProperties properties, AuditService audit) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public boolean setupRequired() {
        return users.count() == 0;
    }

    public boolean setupEnabled() {
        return properties.setupEnabled();
    }

    @Transactional(readOnly = true)
    public Optional<User> byEmail(String email) {
        return email == null ? Optional.empty() : users.findByEmailIgnoreCase(email);
    }

    /** Creates the first administrator. Only possible once, and only with the SETUP_TOKEN from the server's environment. */
    @Transactional
    public User createOwner(String setupToken, String email, String displayName, String password) {
        if (!properties.setupEnabled()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Setup is disabled. Set SETUP_TOKEN on the server.");
        }
        if (!tokensMatch(properties.setupToken(), setupToken)) {
            audit.record("SETUP_REJECTED");
            throw new ApiException(ErrorCode.FORBIDDEN, "Invalid setup token");
        }
        if (users.count() > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Setup is already done. Ask an administrator for an invitation.");
        }
        PasswordPolicy.validate(password);
        try {
            User user = users.saveAndFlush(User.firstAdmin(email.trim(), displayName.trim(), passwordEncoder.encode(password)));
            audit.record("OWNER_CREATED", "user", user.getId(), Map.of());
            return user;
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.CONFLICT, "Setup is already done");
        }
    }

    @Transactional
    public void recordLogin(String email) {
        users.findByEmailIgnoreCase(email).ifPresent(u -> u.setLastLoginAt(Instant.now()));
    }

    @Transactional
    public void changePassword(String email, String currentPassword, String newPassword) {
        User user = users.findByEmailIgnoreCase(email).orElseThrow(() -> ApiException.notFound("User"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            audit.record("PASSWORD_CHANGE_REJECTED", "user", user.getId(), Map.of());
            throw new ApiException(ErrorCode.INVALID_LOGIN, "Current password is incorrect");
        }
        PasswordPolicy.validate(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        audit.record("PASSWORD_CHANGED", "user", user.getId(), Map.of());
    }

    @Transactional
    public User updateProfile(String email, String displayName) {
        User user = users.findByEmailIgnoreCase(email).orElseThrow(() -> ApiException.notFound("User"));
        user.setDisplayName(displayName.trim());
        return user;
    }

    // ---- one-time links ------------------------------------------------------------------------

    public record IssuedLink(String token, Instant expiresAt) {
    }

    /** Makes a new link for {@code user}; any older unused link stops working. Returns the raw token (shown once). */
    @Transactional
    public IssuedLink issue(User user, UserToken.Purpose purpose, UUID issuedBy) {
        Instant now = Instant.now();
        tokens.retireAll(user.getId(), now);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expires = now.plus(purpose == UserToken.Purpose.ACTIVATE ? ACTIVATE_FOR : RESET_FOR);
        tokens.save(new UserToken(user.getId(), purpose, sha256(token), expires, issuedBy));
        audit.record(purpose == UserToken.Purpose.ACTIVATE ? "ACTIVATION_LINK_ISSUED" : "RESET_LINK_ISSUED",
                "user", user.getId(), Map.of());
        return new IssuedLink(token, expires);
    }

    public record LinkInfo(UserToken.Purpose purpose, String email, String displayName, Instant expiresAt) {
    }

    /** What a link is for, so the page can greet the person. Unknown, used and expired links all look the same. */
    @Transactional(readOnly = true)
    public LinkInfo inspect(String token) {
        UserToken t = usable(token);
        User u = users.findById(t.getUserId()).orElseThrow(AuthService::invalidLink);
        return new LinkInfo(t.getPurpose(), u.getEmail(), u.getDisplayName(), t.getExpiresAt());
    }

    /** Sets the password through a link: activates an invited account, or resets an active one. */
    @Transactional
    public User redeem(String token, String password) {
        UserToken t = usable(token);
        User u = users.findById(t.getUserId()).orElseThrow(AuthService::invalidLink);
        if (u.getStatus() == User.Status.DEACTIVATED) throw invalidLink();
        if (t.getPurpose() == UserToken.Purpose.ACTIVATE && u.getStatus() != User.Status.INVITED) throw invalidLink();
        if (t.getPurpose() == UserToken.Purpose.RESET && u.getStatus() != User.Status.ACTIVE) throw invalidLink();
        PasswordPolicy.validate(password);
        t.use();
        if (t.getPurpose() == UserToken.Purpose.ACTIVATE) {
            u.activate(passwordEncoder.encode(password));
            audit.record("ACCOUNT_ACTIVATED", "user", u.getId(), Map.of());
        } else {
            u.setPasswordHash(passwordEncoder.encode(password));
            audit.record("PASSWORD_RESET", "user", u.getId(), Map.of());
        }
        return u;
    }

    private UserToken usable(String token) {
        if (token == null || token.length() < 20 || token.length() > 100) throw invalidLink();
        UserToken t = tokens.findByTokenHash(sha256(token)).orElseThrow(AuthService::invalidLink);
        if (!t.usable(Instant.now())) throw invalidLink();
        return t;
    }

    private static ApiException invalidLink() {
        return new ApiException(ErrorCode.NOT_FOUND, "This link has expired or was already used. Ask an administrator for a new one.");
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean tokensMatch(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
