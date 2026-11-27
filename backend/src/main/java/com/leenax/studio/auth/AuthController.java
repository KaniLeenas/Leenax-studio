package com.leenax.studio.auth;

import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final LoginAttemptLimiter limiter;
    private final AuditService audit;
    private final MfaService mfa;
    private final LoginEventRepository loginEvents;
    private final RegistrationService registration;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public AuthController(AuthService authService, AuthenticationManager authenticationManager,
                          SecurityContextRepository contextRepository, LoginAttemptLimiter limiter,
                          AuditService audit, MfaService mfa, LoginEventRepository loginEvents, RegistrationService registration) {
        this.authService = authService;
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.limiter = limiter;
        this.audit = audit;
        this.mfa = mfa;
        this.loginEvents = loginEvents;
        this.registration = registration;
    }

    /** Session attribute set after a correct password when a second step is needed: "email|expiresEpochSecond|attempts". */
    static final String MFA_PENDING = "LEENAX_MFA_PENDING";
    static final long MFA_PENDING_SECONDS = 300;
    static final int MFA_MAX_ATTEMPTS = 5;

    // ---- DTOs -----------------------------------------------------------

    public record UserResponse(java.util.UUID id, String email, String displayName, User.Role role, boolean canCreateProjects,
                               Instant lastLoginAt, boolean mfaEnabled, Instant mfaEnabledAt, Long recoveryCodesLeft) {
        static UserResponse of(User u) {
            return of(u, null);
        }

        static UserResponse of(User u, Long recoveryCodesLeft) {
            return new UserResponse(u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(), u.canCreateProjects(),
                    u.getLastLoginAt(), u.isMfaEnabled(), u.getMfaEnabledAt(), recoveryCodesLeft);
        }
    }

    public record LinkRequest(@NotBlank @Size(max = 100) String token, @NotBlank @Size(max = 200) String password) {
        @Override
        public String toString() {
            return "LinkRequest[***]";
        }
    }

    /** Either signed in ({@code user} set) or waiting for the authenticator code ({@code mfaRequired}). */
    public record LoginResponse(boolean mfaRequired, UserResponse user) {
    }

    public record CodeRequest(@NotBlank @Size(max = 40) String code) {
        @Override
        public String toString() {
            return "CodeRequest[***]";
        }
    }

    public record PasswordRequest(@NotBlank @Size(max = 200) String password) {
        @Override
        public String toString() {
            return "PasswordRequest[***]";
        }
    }

    public record PasswordCodeRequest(@NotBlank @Size(max = 200) String password, @NotBlank @Size(max = 40) String code) {
        @Override
        public String toString() {
            return "PasswordCodeRequest[***]";
        }
    }

    public record MfaSetupResponse(String secret, String otpauthUri) {
    }

    public record RecoveryCodesResponse(List<String> recoveryCodes) {
    }

    public record StatusResponse(boolean setupRequired, boolean setupEnabled, boolean authenticated, UserResponse user) {
    }

    public record RegistrationStatusResponse(boolean enabled) {
    }

    public record SetupRequest(
            @NotBlank String setupToken,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 80) String displayName,
            @NotBlank @Size(max = 200) String password) {
        @Override
        public String toString() {
            return "SetupRequest[email=" + email + "]";
        }
    }

    public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 200) String password) {
        @Override
        public String toString() {
            return "LoginRequest[email=" + email + "]";
        }
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 200) String currentPassword,
                                        @NotBlank @Size(max = 200) String newPassword) {
        @Override
        public String toString() {
            return "ChangePasswordRequest[***]";
        }
    }

    public record ProfileRequest(@NotBlank @Size(max = 80) String displayName) {
    }

    // ---- Endpoints ------------------------------------------------------

    @GetMapping("/status")
    public StatusResponse status(Authentication authentication) {
        boolean authenticated = authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal());
        UserResponse user = authenticated
                ? authService.byEmail(authentication.getName()).filter(User::isActive).map(UserResponse::of).orElse(null)
                : null;
        return new StatusResponse(authService.setupRequired(), authService.setupEnabled(), authenticated && user != null, user);
    }

    @GetMapping("/registration")
    public RegistrationStatusResponse registrationStatus() {
        return new RegistrationStatusResponse(registration.publicRegistrationEnabled());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RegistrationService.RegistrationResult register(@Valid @RequestBody RegistrationService.RegisterRequest request) {
        return registration.register(request);
    }

    @PostMapping("/setup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse setup(@Valid @RequestBody SetupRequest req, HttpServletRequest request,
                              HttpServletResponse response) {
        String ip = request.getRemoteAddr();
        if (limiter.isLocked(ip)) {
            throw lockedOut();
        }
        try {
            authService.createOwner(req.setupToken(), req.email(), req.displayName(), req.password());
        } catch (ApiException e) {
            if (e.code() == ErrorCode.FORBIDDEN) {
                limiter.recordFailure(ip);
            }
            throw e;
        }
        return signIn(req.email(), req.password(), request, response);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest request,
                               HttpServletResponse response) {
        String ip = request.getRemoteAddr();
        if (limiter.isLocked(ip)) {
            throw lockedOut();
        }
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(req.email().trim(), req.password()));
        } catch (AuthenticationException e) {
            limiter.recordFailure(ip);
            audit.record("LOGIN_FAILED");
            recordAttempt(req.email(), false, failureReason(e), request);
            // The same answer for a wrong password, an unknown email and a deactivated account.
            throw new ApiException(ErrorCode.INVALID_LOGIN, "Incorrect email or password");
        }
        if (mfa.enabled(auth.getName())) {
            // Password is right; the session is not signed in until the second step succeeds.
            if (request.getSession(false) != null) request.changeSessionId();
            request.getSession(true).setAttribute(MFA_PENDING,
                    auth.getName() + "|" + (Instant.now().getEpochSecond() + MFA_PENDING_SECONDS) + "|0");
            audit.record("LOGIN_PASSWORD_OK_MFA_REQUIRED");
            return new LoginResponse(true, null);
        }
        return new LoginResponse(false, establish(auth, request, response));
    }

    /** Second step: a code from the authenticator app, or a recovery code. */
    @PostMapping("/mfa/login")
    public LoginResponse mfaLogin(@Valid @RequestBody CodeRequest req, HttpServletRequest request, HttpServletResponse response) {
        String ip = request.getRemoteAddr();
        if (limiter.isLocked(ip)) {
            throw lockedOut();
        }
        var session = request.getSession(false);
        Object pending = session == null ? null : session.getAttribute(MFA_PENDING);
        if (!(pending instanceof String p)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Sign in with your password first");
        }
        int b = p.lastIndexOf('|');
        int a = p.lastIndexOf('|', b - 1);
        String email = p.substring(0, a);
        long expires = Long.parseLong(p.substring(a + 1, b));
        int attempts = Integer.parseInt(p.substring(b + 1));
        if (Instant.now().getEpochSecond() > expires || attempts >= MFA_MAX_ATTEMPTS) {
            session.removeAttribute(MFA_PENDING);
            throw new ApiException(ErrorCode.UNAUTHORIZED, "That took too long or too many tries. Sign in again.");
        }
        if (!mfa.check(email, req.code())) {
            session.setAttribute(MFA_PENDING, email + "|" + expires + "|" + (attempts + 1));
            limiter.recordFailure(ip);
            audit.record("LOGIN_MFA_FAILED");
            recordAttempt(email, false, "wrong_code", request);
            throw new ApiException(ErrorCode.INVALID_LOGIN, "That code didn't match");
        }
        session.removeAttribute(MFA_PENDING);
        User signingIn = authService.byEmail(email).filter(User::isActive)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Sign in with your password first"));
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(signingIn.getEmail(), null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + signingIn.getRole().name())));
        return new LoginResponse(false, establish(auth, request, response));
    }

    @PostMapping("/mfa/setup")
    public MfaSetupResponse mfaSetup(@Valid @RequestBody PasswordRequest req, Authentication authentication) {
        MfaService.Setup s = mfa.begin(authentication.getName(), req.password());
        return new MfaSetupResponse(s.secret(), s.otpauthUri());
    }

    @PostMapping("/mfa/enable")
    public RecoveryCodesResponse mfaEnable(@Valid @RequestBody CodeRequest req, Authentication authentication) {
        return new RecoveryCodesResponse(mfa.confirm(authentication.getName(), req.code()));
    }

    @PostMapping("/mfa/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mfaDisable(@Valid @RequestBody PasswordCodeRequest req, Authentication authentication) {
        mfa.disable(authentication.getName(), req.password(), req.code());
    }

    @PostMapping("/mfa/recovery-codes")
    public RecoveryCodesResponse mfaRecoveryCodes(@Valid @RequestBody PasswordCodeRequest req, Authentication authentication) {
        return new RecoveryCodesResponse(mfa.regenerate(authentication.getName(), req.password(), req.code()));
    }

    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        User u = authService.byEmail(authentication.getName()).orElseThrow(() -> ApiException.notFound("User"));
        return UserResponse.of(u, mfa.recoveryCodesLeft(u));
    }

    /** What an activation or reset link is for. No sign-in needed: the link itself is the secret. */
    @GetMapping("/link")
    public AuthService.LinkInfo link(@RequestParam String token, HttpServletRequest request) {
        if (limiter.isLocked(request.getRemoteAddr())) throw lockedOut();
        try {
            return authService.inspect(token);
        } catch (ApiException e) {
            limiter.recordFailure(request.getRemoteAddr());
            throw e;
        }
    }

    /** Sets the password from a link: activates an invited account or resets a forgotten password. */
    @PostMapping("/link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void redeemLink(@Valid @RequestBody LinkRequest req, HttpServletRequest request) {
        if (limiter.isLocked(request.getRemoteAddr())) throw lockedOut();
        try {
            authService.redeem(req.token(), req.password());
        } catch (ApiException e) {
            if (e.code() == ErrorCode.NOT_FOUND) limiter.recordFailure(request.getRemoteAddr());
            throw e;
        }
    }

    @PatchMapping("/me")
    public UserResponse updateProfile(@Valid @RequestBody ProfileRequest req, Authentication authentication) {
        return UserResponse.of(authService.updateProfile(authentication.getName(), req.displayName()));
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest req, Authentication authentication) {
        authService.changePassword(authentication.getName(), req.currentPassword(), req.newPassword());
    }

    // ---- Helpers --------------------------------------------------------

    private UserResponse signIn(String email, String password, HttpServletRequest request,
                                HttpServletResponse response) {
        Authentication auth = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email.trim(), password));
        return establish(auth, request, response);
    }

    private UserResponse establish(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        // Prevent session fixation: new session id on every login.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(auth);
        contextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        limiter.recordSuccess(request.getRemoteAddr());
        authService.recordLogin(auth.getName());
        audit.record("LOGIN_SUCCEEDED", null, null, Map.of());
        recordAttempt(auth.getName(), true, null, request);
        return authService.byEmail(auth.getName()).map(UserResponse::of).orElseThrow();
    }

    private void recordAttempt(String email, boolean success, String reason, HttpServletRequest request) {
        try {
            java.util.UUID userId = authService.byEmail(email).map(User::getId).orElse(null);
            loginEvents.save(new LoginEvent(userId, email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT), success, reason,
                    request.getRemoteAddr(), request.getHeader("User-Agent")));
        } catch (RuntimeException e) {
            // the history is useful, but never a reason to refuse a sign-in
        }
    }

    static String failureReason(AuthenticationException e) {
        String name = e.getClass().getSimpleName();
        if (name.startsWith("Disabled") || name.startsWith("Locked") || name.startsWith("AccountExpired")) return "account_not_active";
        return "wrong_password";
    }

    private ApiException lockedOut() {
        return new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED,
                "Too many failed attempts. Try again in " + limiter.lockoutMinutes() + " minutes.");
    }
}
