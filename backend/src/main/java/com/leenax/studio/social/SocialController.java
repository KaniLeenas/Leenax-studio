package com.leenax.studio.social;

import com.leenax.studio.common.ApiException;
import com.leenax.studio.social.SocialDtos.AccountResponse;
import com.leenax.studio.social.SocialDtos.CreatorInfoResponse;
import com.leenax.studio.social.SocialDtos.PlatformInfo;
import com.leenax.studio.social.SocialDtos.StartResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/social")
public class SocialController {

    private final SocialAccountService service;

    public SocialController(SocialAccountService service) {
        this.service = service;
    }

    @GetMapping("/platforms")
    public List<PlatformInfo> platforms() {
        return service.platforms();
    }

    @GetMapping("/accounts")
    public List<AccountResponse> accounts() {
        return service.list();
    }

    /** Returns the platform's sign-in page; the browser navigates there. */
    @PostMapping("/oauth/{provider}/start")
    public StartResponse start(@PathVariable String provider) {
        return new StartResponse(service.start(parse(provider)));
    }

    /**
     * The sign-in page sends the browser here. Open to the network (the session
     * cookie is SameSite=Strict, so it is not sent on this cross-site redirect);
     * the single-use state value is what ties it to a sign-in the owner started.
     */
    @GetMapping("/oauth/{provider}/callback")
    public ResponseEntity<Void> callback(@PathVariable String provider,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @RequestParam(name = "error_description", required = false) String errorDescription) {
        String target;
        try {
            target = service.callback(parse(provider), code, state, error, errorDescription);
        } catch (ApiException e) {
            target = "/publish/accounts?error=Unknown+sign-in+provider";
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    @PostMapping("/accounts/{id}/check")
    public AccountResponse check(@PathVariable UUID id) {
        return service.check(id);
    }

    @GetMapping("/accounts/{id}/tiktok-creator")
    public CreatorInfoResponse tiktokCreator(@PathVariable UUID id) {
        return service.tiktokCreator(id);
    }

    @DeleteMapping("/accounts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable UUID id) {
        service.disconnect(id);
    }

    static SocialPlatform.AuthProvider parse(String provider) {
        try {
            return SocialPlatform.AuthProvider.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Sign-in provider");
        }
    }
}
