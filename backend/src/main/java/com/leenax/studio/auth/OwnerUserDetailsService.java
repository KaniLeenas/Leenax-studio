package com.leenax.studio.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Loads a person for sign-in. Only active accounts with a password can sign in. */
@Service
public class OwnerUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public OwnerUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        User user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
        boolean canSignIn = user.isActive() && user.getPasswordHash() != null;
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                // a value that never matches, so Spring still runs the hash check and timing stays even
                .password(user.getPasswordHash() != null ? user.getPasswordHash() : "$2a$12$000000000000000000000uGWjcB5qJqY5y3ZbNk8iQ2Yj8xJ5x7sK")
                .roles(user.getRole().name())
                .disabled(!canSignIn)
                .build();
    }
}
