package com.leenax.studio.auth;

import com.leenax.studio.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock JdbcTemplate jdbc;
    @Mock UserRepository users;
    @Mock AuthService auth;
    @Mock CurrentUser current;
    @Mock PasswordEncoder passwords;
    @Mock ActivationEmailSender email;
    @Mock AuditService audit;

    RegistrationService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationService(jdbc, users, auth, current, passwords, email, audit);
    }

    @Test
    void publicModeRequiresConfiguredEmail() {
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("PUBLIC");
        when(email.isConfigured()).thenReturn(false);
        assertFalse(service.publicRegistrationEnabled());
        when(email.isConfigured()).thenReturn(true);
        assertTrue(service.publicRegistrationEnabled());
    }

    @Test
    void publicRegistrationStoresOnlyHashAndSendsActivationLink() {
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("PUBLIC");
        when(email.isConfigured()).thenReturn(true);
        when(users.findByEmailIgnoreCase("asha@example.com")).thenReturn(Optional.empty());
        when(passwords.encode("a long secure password" )).thenReturn("bcrypt-hash");
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(auth.issue(any(User.class), eq(UserToken.Purpose.ACTIVATE), isNull()))
                .thenReturn(new AuthService.IssuedLink("one-time-token", java.time.Instant.now().plusSeconds(600)));

        var result = service.register(new RegistrationService.RegisterRequest(
                "Asha@Example.com", "Asha", "a long secure password", "a long secure password"));

        assertEquals(RegistrationService.CHECK_EMAIL_MESSAGE, result.message());
        verify(users).saveAndFlush(argThat(u -> u.getEmail().equals("asha@example.com")
                && u.getStatus() == User.Status.INVITED
                && u.getRole() == User.Role.MEMBER
                && "bcrypt-hash".equals(u.getPasswordHash())));
        verify(email).sendActivation("asha@example.com", "Asha", "one-time-token");
        verify(auth).issue(any(User.class), eq(UserToken.Purpose.ACTIVATE), isNull());
    }

    @Test
    void duplicateEmailGetsSameResponseWithoutSendingAnotherLink() {
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("PUBLIC");
        when(email.isConfigured()).thenReturn(true);
        when(users.findByEmailIgnoreCase("asha@example.com")).thenReturn(Optional.of(new User()));

        var result = service.register(new RegistrationService.RegisterRequest(
                "asha@example.com", "Asha", "a long secure password", "a long secure password"));

        assertEquals(RegistrationService.CHECK_EMAIL_MESSAGE, result.message());
        verify(users, never()).saveAndFlush(any());
        verify(email, never()).sendActivation(anyString(), anyString(), anyString());
    }

    @Test
    void mismatchedPasswordsDoNotCreateAnAccount() {
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("PUBLIC");
        when(email.isConfigured()).thenReturn(true);

        assertThrows(RuntimeException.class, () -> service.register(new RegistrationService.RegisterRequest(
                "asha@example.com", "Asha", "a long secure password", "a different password")));
        verify(users, never()).saveAndFlush(any());
    }
}
