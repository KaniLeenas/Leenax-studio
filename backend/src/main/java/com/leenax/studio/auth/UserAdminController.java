package com.leenax.studio.auth;

import com.leenax.studio.auth.UserAdminDtos.*;
import com.leenax.studio.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** People and access. Everything except the colleague list is for administrators. */
@RestController
@RequestMapping("/api/v1/users")
public class UserAdminController {

    private final UserAdminService service;
    private final RegistrationService registration;

    public UserAdminController(UserAdminService service, RegistrationService registration) {
        this.service = service;
        this.registration = registration;
    }

    @GetMapping
    public UsersResponse list(@RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "50") int size,
                              @RequestParam(required = false) String search,
                              @RequestParam(required = false) User.Status status) {
        return service.list(page, size, search, status);
    }

    @GetMapping("/registration")
    public RegistrationSettings registrationSettings() {
        return registration.settings();
    }

    @PutMapping("/registration")
    public RegistrationSettings updateRegistrationSettings(@Valid @RequestBody RegistrationModeRequest request) {
        return registration.updateMode(request);
    }

    @GetMapping("/colleagues")
    public PageResponse<Colleague> colleagues(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(required = false) String search) {
        return service.colleagues(page, size, search);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LinkResponse invite(@Valid @RequestBody InviteRequest r) {
        return service.invite(r);
    }

    @PutMapping("/{id}")
    public Person update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest r) {
        return service.update(id, r);
    }

    @PostMapping("/{id}/deactivate")
    public Person deactivate(@PathVariable UUID id) {
        return service.deactivate(id);
    }

    @PostMapping("/{id}/reactivate")
    public Person reactivate(@PathVariable UUID id) {
        return service.reactivate(id);
    }

    @PostMapping("/{id}/activation-link")
    public LinkResponse activationLink(@PathVariable UUID id) {
        return service.activationLink(id);
    }

    @PostMapping("/{id}/reset-link")
    public LinkResponse resetLink(@PathVariable UUID id) {
        return service.resetLink(id);
    }

    @GetMapping("/logins")
    public PageResponse<LoginResponse> logins(@RequestParam(required = false) UUID userId,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int size) {
        return service.logins(userId, page, size);
    }
}
