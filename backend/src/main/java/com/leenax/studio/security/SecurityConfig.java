package com.leenax.studio.security;

import com.leenax.studio.auth.OwnerUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Single-owner security model:
 * - Session cookie (HttpOnly, SameSite=Strict) backed by Redis; no tokens in browser storage.
 * - CSRF protection on every state-changing request.
 * - Everything under /api requires login except status, setup and login.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contextRepository,
                                            com.leenax.studio.auth.UserRepository users)
            throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(c -> c.sameSite("Strict").path("/"));

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepository)
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
            .securityContext(ctx -> ctx.securityContextRepository(contextRepository))
            .sessionManagement(s -> s
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(f -> f.changeSessionId()))
            .authorizeHttpRequests(auth -> auth
                // SSE and error re-dispatches reuse the original, already-authorised request
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/status").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/setup", "/api/v1/auth/login", "/api/v1/auth/mfa/login").permitAll()
                // Activation and password-reset links: the one-time token in the request is the credential.
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/link").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/link").permitAll()
                // Sign-in pages redirect here cross-site, so the Strict session cookie is absent;
                // the single-use OAuth state (hashed in the database) authorises the request instead.
                .requestMatchers(HttpMethod.GET, "/api/v1/social/oauth/*/callback").permitAll()
                // Administrators only: people, keys, money, connected channels, health and the audit trail.
                // (Services check again; the database's row-level security limits what members can see.)
                .requestMatchers(HttpMethod.GET, "/api/v1/users/colleagues").authenticated()
                .requestMatchers("/api/v1/users", "/api/v1/users/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/system/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/ai/accounts", "/api/v1/ai/accounts/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/ai/accounts/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/ai/accounts/**", "/api/v1/ai/models/**", "/api/v1/ai/balances/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/ai/accounts/**", "/api/v1/ai/balances/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/ai/usage", "/api/v1/ai/usage/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/social/oauth/*/start", "/api/v1/social/accounts/*/check").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/social/accounts/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .addFilterBefore(new com.leenax.studio.access.AccountStatusFilter(users, contextRepository),
                    org.springframework.security.web.access.intercept.AuthorizationFilter.class)
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l
                .logoutUrl("/api/v1/auth/logout")
                .deleteCookies("LEENAX_SESSION")
                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
            .headers(h -> h
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")));
        return http.build();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AuthenticationManager authenticationManager(OwnerUserDetailsService userDetailsService, PasswordEncoder encoder) {
        // Constructor + setters exist across all Spring Security 6.x releases (may show a deprecation warning).
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
}
