package com.leenax.studio.auth;

import com.leenax.studio.audit.AuditService;
import com.leenax.studio.auth.UserAdminDtos.*;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.common.PageResponse;
import com.leenax.studio.common.SqlStates;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The administrator's side of accounts. The seat limit and "keep one administrator"
 * are checked here for a clear message, and again by the database (V9) so no path
 * can get around them.
 */
@Service
public class UserAdminService {

    static final String ACTIVATE_PATH = "/activate?token=";
    static final String RESET_PATH = "/reset-password?token=";

    private final UserRepository users;
    private final AuthService auth;
    private final CurrentUser current;
    private final LoginEventRepository logins;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public UserAdminService(UserRepository users, AuthService auth, CurrentUser current, LoginEventRepository logins,
                            JdbcTemplate jdbc, AuditService audit) {
        this.users = users;
        this.auth = auth;
        this.current = current;
        this.logins = logins;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public UsersResponse list() {
        current.requireAdmin();
        return new UsersResponse(users.findAllByOrderByCreatedAtAsc().stream().map(Person::of).toList(), seats());
    }

    @Transactional(readOnly = true)
    public List<Colleague> colleagues() {
        current.require();
        return users.findByStatusOrderByDisplayNameAsc(User.Status.ACTIVE).stream()
                .map(u -> new Colleague(u.getId(), u.getDisplayName(), u.getEmail())).toList();
    }

    @Transactional
    public LinkResponse invite(InviteRequest r) {
        User admin = current.requireAdmin();
        String email = r.email().strip();
        if (users.findByEmailIgnoreCase(email).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT, "Someone with that email already has an account");
        }
        requireFreeSeat();
        User u = save(User.invited(email, r.displayName().strip(), r.role(), r.canCreateProjects(), admin.getId()));
        audit.record("USER_INVITED", "user", u.getId(), Map.of("role", r.role().name()));
        AuthService.IssuedLink link = auth.issue(u, UserToken.Purpose.ACTIVATE, admin.getId());
        return new LinkResponse(Person.of(u), ACTIVATE_PATH + link.token(), link.expiresAt());
    }

    @Transactional
    public Person update(UUID id, UpdateRequest r) {
        User admin = current.requireAdmin();
        User u = find(id);
        if (u.isAdmin() && r.role() != User.Role.ADMIN && u.isActive() && otherActiveAdmins(u) == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Make someone else an administrator first");
        }
        User.Role before = u.getRole();
        u.setDisplayName(r.displayName().strip());
        u.setRole(r.role());
        u.setCanCreateProjects(r.canCreateProjects());
        save(u);
        if (before != r.role()) {
            audit.record("USER_ROLE_CHANGED", "user", u.getId(), Map.of("from", before.name(), "to", r.role().name(),
                    "by", admin.getId().toString()));
        }
        return Person.of(u);
    }

    @Transactional
    public Person deactivate(UUID id) {
        User admin = current.requireAdmin();
        User u = find(id);
        if (u.getId().equals(admin.getId())) {
            throw new ApiException(ErrorCode.CONFLICT, "You can't deactivate your own account");
        }
        if (u.getStatus() == User.Status.DEACTIVATED) return Person.of(u);
        u.deactivate();
        save(u);
        audit.record("USER_DEACTIVATED", "user", u.getId(), Map.of());
        return Person.of(u);
    }

    @Transactional
    public Person reactivate(UUID id) {
        current.requireAdmin();
        User u = find(id);
        if (u.getStatus() != User.Status.DEACTIVATED) return Person.of(u);
        requireFreeSeat();
        u.reactivate();
        save(u);
        audit.record("USER_REACTIVATED", "user", u.getId(), Map.of());
        return Person.of(u);
    }

    /** A fresh activation link for someone who hasn't set a password yet. */
    @Transactional
    public LinkResponse activationLink(UUID id) {
        User admin = current.requireAdmin();
        User u = find(id);
        if (u.getStatus() != User.Status.INVITED) {
            throw new ApiException(ErrorCode.CONFLICT, u.isActive()
                    ? "This account is already active. Make a password reset link instead."
                    : "Reactivate the account first");
        }
        AuthService.IssuedLink link = auth.issue(u, UserToken.Purpose.ACTIVATE, admin.getId());
        return new LinkResponse(Person.of(u), ACTIVATE_PATH + link.token(), link.expiresAt());
    }

    /** A password reset link for an active account (there's no email: the administrator passes it on). */
    @Transactional
    public LinkResponse resetLink(UUID id) {
        User admin = current.requireAdmin();
        User u = find(id);
        if (!u.isActive()) {
            throw new ApiException(ErrorCode.CONFLICT, "Only an active account can reset its password");
        }
        AuthService.IssuedLink link = auth.issue(u, UserToken.Purpose.RESET, admin.getId());
        return new LinkResponse(Person.of(u), RESET_PATH + link.token(), link.expiresAt());
    }

    @Transactional
    public Seats setSeats(SeatsRequest r) {
        User admin = current.requireAdmin();
        long used = users.countByStatusIn(EnumSet.of(User.Status.ACTIVE, User.Status.INVITED));
        if (r.maxActiveUsers() < used) {
            throw new ApiException(ErrorCode.CONFLICT, used + " seats are in use. Deactivate someone before lowering the limit.");
        }
        jdbc.update("update app_settings set value = ?, updated_at = now() where key = 'max_active_users'",
                String.valueOf(r.maxActiveUsers()));
        audit.record("SEAT_LIMIT_CHANGED", "user", admin.getId(), Map.of("limit", r.maxActiveUsers()));
        return seats();
    }

    @Transactional(readOnly = true)
    public PageResponse<LoginResponse> logins(UUID userId, int page, int size) {
        current.requireAdmin();
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 100));
        var result = userId == null ? logins.findAllByOrderByCreatedAtDesc(pageable)
                : logins.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return PageResponse.of(result.map(LoginResponse::of));
    }

    // ---- helpers -----------------------------------------------------------------------------------

    Seats seats() {
        Integer limit = jdbc.queryForObject("select value::int from app_settings where key = 'max_active_users'", Integer.class);
        return new Seats(users.countByStatusIn(EnumSet.of(User.Status.ACTIVE, User.Status.INVITED)), limit == null ? 5 : limit);
    }

    private void requireFreeSeat() {
        Seats s = seats();
        if (s.used() >= s.limit()) {
            throw new ApiException(ErrorCode.CONFLICT, "All " + s.limit()
                    + " seats are taken. Deactivate someone or raise the limit.");
        }
    }

    private long otherActiveAdmins(User u) {
        return users.findAllByOrderByCreatedAtAsc().stream()
                .filter(x -> x.isAdmin() && x.isActive() && !x.getId().equals(u.getId())).count();
    }

    private User find(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }

    /** Saves and flushes so the database's own checks (seats, last administrator) answer here, with a clear message. */
    private User save(User u) {
        try {
            return users.saveAndFlush(u);
        } catch (RuntimeException e) {
            throw SqlStates.translate(e).orElseThrow(() -> e);
        }
    }
}
