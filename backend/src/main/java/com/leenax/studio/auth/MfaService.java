package com.leenax.studio.auth;

import com.leenax.studio.ai.crypto.SecretCipher;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Optional two-step sign-in with an authenticator app (TOTP), plus ten
 * single-use recovery codes. The secret is encrypted with LEENAX_MASTER_KEY;
 * recovery codes are kept only as SHA-256 hashes.
 */
@Service
public class MfaService {

    public static final String ISSUER = "Leenax Studio";
    static final int RECOVERY_CODES = 10;
    private static final String CODE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";   // no 0/o, 1/l/i
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Setup(String secret, String otpauthUri) {
        @Override
        public String toString() {
            return "Setup[hidden]";
        }
    }

    private final UserRepository users;
    private final MfaRecoveryCodeRepository codes;
    private final SecretCipher cipher;
    private final PasswordEncoder passwords;
    private final AuditService audit;

    public MfaService(UserRepository users, MfaRecoveryCodeRepository codes, SecretCipher cipher,
                      PasswordEncoder passwords, AuditService audit) {
        this.users = users;
        this.codes = codes;
        this.cipher = cipher;
        this.passwords = passwords;
        this.audit = audit;
    }

    static String aad(User u) {
        return "user_mfa:" + u.getId();
    }

    @Transactional(readOnly = true)
    public boolean enabled(String email) {
        return users.findByEmailIgnoreCase(email).map(User::isMfaEnabled).orElse(false);
    }

    @Transactional(readOnly = true)
    public long recoveryCodesLeft(User u) {
        return u.isMfaEnabled() ? codes.countByUserIdAndUsedAtIsNull(u.getId()) : 0;
    }

    /** Step 1: after the password is confirmed, a new secret to scan. Nothing changes until it is confirmed. */
    @Transactional
    public Setup begin(String email, String password) {
        User u = user(email);
        checkPassword(u, password, "MFA_SETUP_REJECTED");
        if (u.isMfaEnabled()) throw new ApiException(ErrorCode.CONFLICT, "Two-step sign-in is already on");
        String secret = Totp.newSecret();
        u.setMfaPendingSecret(cipher.encrypt(secret, aad(u)));
        return new Setup(secret, Totp.uri(ISSUER, u.getEmail(), secret));
    }

    /** Step 2: the first code from the app turns it on and returns the recovery codes (shown once). */
    @Transactional
    public List<String> confirm(String email, String code) {
        User u = user(email);
        if (u.getMfaPendingSecret() == null) throw new ApiException(ErrorCode.CONFLICT, "Start the set-up again");
        String secret = cipher.decrypt(u.getMfaPendingSecret(), aad(u));
        long step = Totp.verify(secret, code, Instant.now(), null);
        if (step < 0) throw new ApiException(ErrorCode.INVALID_LOGIN, "That code didn't match. Check the time on your phone and try the next code.");
        u.setMfaSecret(u.getMfaPendingSecret());
        u.setMfaPendingSecret(null);
        u.setMfaEnabledAt(Instant.now());
        u.setMfaLastStep(step);
        List<String> plain = replaceRecoveryCodes(u);
        audit.record("MFA_ENABLED", "user", u.getId(), Map.of());
        return plain;
    }

    @Transactional
    public void disable(String email, String password, String code) {
        User u = user(email);
        checkPassword(u, password, "MFA_DISABLE_REJECTED");
        if (!u.isMfaEnabled()) return;
        if (!check(u, code)) {
            audit.record("MFA_DISABLE_REJECTED", "user", u.getId(), Map.of());
            throw new ApiException(ErrorCode.INVALID_LOGIN, "That code didn't match");
        }
        u.setMfaSecret(null);
        u.setMfaPendingSecret(null);
        u.setMfaEnabledAt(null);
        u.setMfaLastStep(null);
        codes.deleteAllForUser(u.getId());
        audit.record("MFA_DISABLED", "user", u.getId(), Map.of());
    }

    @Transactional
    public List<String> regenerate(String email, String password, String code) {
        User u = user(email);
        checkPassword(u, password, "MFA_CODES_REJECTED");
        if (!u.isMfaEnabled()) throw new ApiException(ErrorCode.CONFLICT, "Two-step sign-in is off");
        if (!check(u, code)) throw new ApiException(ErrorCode.INVALID_LOGIN, "That code didn't match");
        List<String> plain = replaceRecoveryCodes(u);
        audit.record("MFA_RECOVERY_CODES_REPLACED", "user", u.getId(), Map.of());
        return plain;
    }

    /** At sign-in: a 6-digit code from the app, or one unused recovery code (used up on success). */
    @Transactional
    public boolean check(String email, String code) {
        return check(user(email), code);
    }

    private boolean check(User u, String code) {
        if (!u.isMfaEnabled() || code == null) return false;
        String c = code.strip();
        if (c.replaceAll("\\s", "").matches("\\d{6}")) {
            long step = Totp.verify(cipher.decrypt(u.getMfaSecret(), aad(u)), c, Instant.now(), u.getMfaLastStep());
            if (step < 0) return false;
            u.setMfaLastStep(step);
            return true;
        }
        String hash = hash(normalise(c));
        for (MfaRecoveryCode rc : codes.findByUserIdAndUsedAtIsNull(u.getId())) {
            if (MessageDigest.isEqual(rc.getCodeHash().getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII))) {
                rc.markUsed();
                audit.record("MFA_RECOVERY_CODE_USED", "user", u.getId(), Map.of());
                return true;
            }
        }
        return false;
    }

    private List<String> replaceRecoveryCodes(User u) {
        codes.deleteAllForUser(u.getId());
        List<String> plain = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String c = recoveryCode();
            plain.add(c);
            codes.save(new MfaRecoveryCode(u.getId(), hash(normalise(c))));
        }
        return plain;
    }

    /** Ten characters as xxxxx-xxxxx: about 49 bits each. */
    static String recoveryCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) sb.append('-');
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    static String normalise(String code) {
        return code.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private User user(String email) {
        return users.findByEmailIgnoreCase(email).orElseThrow(() -> ApiException.notFound("User"));
    }

    private void checkPassword(User u, String password, String auditAction) {
        if (password == null || !passwords.matches(password, u.getPasswordHash())) {
            audit.record(auditAction, "user", u.getId(), Map.of());
            throw new ApiException(ErrorCode.INVALID_LOGIN, "Password is incorrect");
        }
    }
}
