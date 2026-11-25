package com.leenax.studio.auth;

import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** The signed-in person for the current request, read fresh from the database. */
@Component
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    public Optional<User> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            return Optional.empty();
        }
        return users.findByEmailIgnoreCase(auth.getName()).filter(User::isActive);
    }

    public User require() {
        return find().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED, "Sign in first"));
    }

    public UUID id() {
        return require().getId();
    }

    public boolean isAdmin() {
        return find().map(User::isAdmin).orElse(false);
    }

    public User requireAdmin() {
        User u = require();
        if (!u.isAdmin()) throw new ApiException(ErrorCode.FORBIDDEN, "Only an administrator can do this");
        return u;
    }
}
