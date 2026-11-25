package com.leenax.studio.social;

import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.social.SocialDtos.AccountResponse;
import com.leenax.studio.social.SocialDtos.CreatorInfoResponse;
import com.leenax.studio.social.SocialDtos.PlatformInfo;
import com.leenax.studio.social.SocialException.Kind;
import com.leenax.studio.social.SocialPlatform.AuthProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connecting accounts through each platform's own sign-in page, keeping their
 * tokens fresh, and disconnecting them. Tokens never leave the backend.
 */
@Service
public class SocialAccountService {

    private static final Logger log = LoggerFactory.getLogger(SocialAccountService.class);
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private final SocialProperties props;
    private final SocialAccountRepository accounts;
    private final OAuthStateRepository states;
    private final TokenVault vault;
    private final YouTubeClient youtube;
    private final MetaClient meta;
    private final TikTokClient tiktok;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Map<UUID, Object> refreshLocks = new ConcurrentHashMap<>();

    public SocialAccountService(SocialProperties props, SocialAccountRepository accounts, OAuthStateRepository states,
                                TokenVault vault, YouTubeClient youtube, MetaClient meta, TikTokClient tiktok,
                                AuditService audit, JdbcTemplate jdbc) {
        this.props = props;
        this.accounts = accounts;
        this.states = states;
        this.vault = vault;
        this.youtube = youtube;
        this.meta = meta;
        this.tiktok = tiktok;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    // ---- read ------------------------------------------------------------------

    public List<PlatformInfo> platforms() {
        List<PlatformInfo> out = new ArrayList<>();
        for (SocialPlatform p : SocialPlatform.values()) {
            AuthProvider a = p.authProvider();
            out.add(new PlatformInfo(p, p.displayName(), a, props.configured(a), props.redirectUri(a), setupHint(a), limits(p)));
        }
        return out;
    }

    static String setupHint(AuthProvider a) {
        return switch (a) {
            case GOOGLE -> "Create an OAuth client (Web application) in Google Cloud, enable YouTube Data API v3, then set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET.";
            case META -> "Create a Business app at developers.facebook.com with Facebook Login for Business, then set META_APP_ID and META_APP_SECRET.";
            case TIKTOK -> "Create an app at developers.tiktok.com with Login Kit and Content Posting API (Direct Post on), then set TIKTOK_CLIENT_KEY and TIKTOK_CLIENT_SECRET.";
        };
    }

    /** What each platform's official API allows, shown before connecting. */
    static List<String> limits(SocialPlatform p) {
        return switch (p) {
            case YOUTUBE -> List.of(
                    "Until your Google Cloud project passes YouTube's API audit, uploads stay private.",
                    "Uploads have their own daily quota (about 100 per project).");
            case FACEBOOK -> List.of(
                    "Posts to Facebook Pages you manage, as Reels (3–90 seconds, vertical).",
                    "Personal profiles and groups cannot be posted to through the API.");
            case INSTAGRAM -> List.of(
                    "Needs an Instagram professional account linked to a Facebook Page.",
                    "Posts as Reels (3 seconds to 15 minutes, up to 300 MB); 100 API posts per 24 hours.");
            case TIKTOK -> List.of(
                    "Until your TikTok app passes audit, posts are visible only to you and the account must be private.",
                    "You choose privacy and interaction settings each time, as TikTok requires.");
        };
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list() {
        return accounts.findAllByOrderByPlatformAscDisplayNameAsc().stream().map(AccountResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public SocialAccount find(UUID id) {
        return accounts.findById(id).orElseThrow(() -> ApiException.notFound("Social account"));
    }

    // ---- connect -----------------------------------------------------------------

    @Transactional
    public String start(AuthProvider provider) {
        if (!props.configured(provider)) {
            throw new ApiException(ErrorCode.CONFLICT, setupHint(provider));
        }
        states.deleteExpired(Instant.now());
        String state = Pkce.randomToken();
        String verifier = provider == AuthProvider.GOOGLE ? Pkce.randomToken() : null;
        states.save(new OAuthState(Pkce.sha256Hex(state), provider, verifier, Instant.now().plus(STATE_TTL)));
        audit.record("SOCIAL_CONNECT_STARTED", "social_account", null, Map.of("provider", provider.name()));
        return switch (provider) {
            case GOOGLE -> youtube.authorizationUrl(state, Pkce.challenge(verifier));
            case META -> meta.authorizationUrl(state);
            case TIKTOK -> tiktok.authorizationUrl(state);
        };
    }

    /**
     * Handles the browser coming back from a sign-in page. Always returns a
     * same-site path to send the browser to, with the outcome in the query.
     */
    @Transactional
    public String callback(AuthProvider provider, String code, String state, String error, String errorDescription) {
        OAuthState saved = state == null ? null : states.findById(Pkce.sha256Hex(state)).orElse(null);
        if (saved == null || saved.getProvider() != provider || saved.getExpiresAt().isBefore(Instant.now())) {
            if (saved != null) states.delete(saved);
            return result(null, "The sign-in link expired or was not started here. Try connecting again.");
        }
        states.delete(saved);   // single use
        if (error != null) {
            String why = "access_denied".equals(error) || "user_denied".equals(error)
                    ? "Sign-in was cancelled." : "Sign-in failed: " + SocialHttp.clip(errorDescription != null ? errorDescription : error);
            return result(null, why);
        }
        if (code == null || code.isBlank()) return result(null, "The sign-in page did not return a code.");
        try {
            int connected = switch (provider) {
                case GOOGLE -> connectGoogle(code, saved.getCodeVerifier());
                case META -> connectMeta(code);
                case TIKTOK -> connectTikTok(code);
            };
            if (connected == 0) {
                return result(null, provider == AuthProvider.META
                        ? "No Facebook Pages were shared. Reconnect and select at least one Page (and its Instagram account)."
                        : "No account was connected.");
            }
            return result(connected, null);
        } catch (SocialException e) {
            log.warn("{} sign-in failed: {}", provider, e.getMessage());
            return result(null, e.getMessage());
        }
    }

    private static String result(Integer connected, String error) {
        String base = "/publish/accounts?";
        return error == null ? base + "connected=" + connected
                : base + "error=" + URLEncoder.encode(error, StandardCharsets.UTF_8);
    }

    private int connectGoogle(String code, String verifier) {
        TokenSet t = youtube.exchangeCode(code, verifier);
        if (t.refreshToken() == null) {
            throw new SocialException(Kind.REJECTED, "Google did not grant offline access. Try connecting again.");
        }
        Profile p = youtube.channel(t.accessToken());
        save(p, t);
        return 1;
    }

    private int connectMeta(String code) {
        TokenSet user = meta.exchangeCode(code);
        int n = 0;
        for (MetaClient.Page page : meta.pages(user.accessToken())) {
            if (page.pageToken() == null) continue;
            // Page tokens obtained from a long-lived user token do not expire.
            TokenSet pageToken = new TokenSet(page.pageToken(), null, null, null, user.scopes());
            save(new Profile(SocialPlatform.FACEBOOK, page.id(), page.name(), null, page.pictureUrl()), pageToken);
            n++;
            if (page.instagramId() != null) {
                save(new Profile(SocialPlatform.INSTAGRAM, page.instagramId(),
                        page.instagramUsername() != null ? "@" + page.instagramUsername() : page.name() + " (Instagram)",
                        page.instagramUsername(), page.instagramPicture(),
                        Map.of("pageId", page.id(), "pageName", page.name())), pageToken);
                n++;
            }
        }
        return n;
    }

    private int connectTikTok(String code) {
        TokenSet t = tiktok.exchangeCode(code);
        Profile p = tiktok.user(t.accessToken(), null);
        save(p, t);
        return 1;
    }

    private void save(Profile p, TokenSet t) {
        SocialAccount a = accounts.findByPlatformAndExternalId(p.platform(), p.externalId())
                .orElseGet(() -> new SocialAccount(p.platform(), p.externalId(), p.displayName()));
        a.setDisplayName(p.displayName());
        if (p.username() != null) a.setUsername(p.username());
        a.setAvatarUrl(p.avatarUrl());
        a.setScopes(t.scopes());
        a.setAccessExpiresAt(t.accessExpiresAt());
        a.setRefreshExpiresAt(t.refreshExpiresAt());
        Map<String, Object> details = new HashMap<>(a.getDetails());
        details.putAll(p.details());
        a.setDetails(details);
        a.markConnected();
        a = accounts.saveAndFlush(a);
        vault.store(a.getId(), t.accessToken(), t.refreshToken());
        audit.record("SOCIAL_ACCOUNT_CONNECTED", "social_account", a.getId(),
                Map.of("platform", p.platform().name(), "name", a.getDisplayName()));
    }

    // ---- tokens ------------------------------------------------------------------

    /**
     * A usable access token, refreshed first when it is about to expire. When the
     * platform says the sign-in is gone, the account is flagged for reconnecting.
     */
    public String accessToken(UUID accountId) {
        SocialAccount a = find(accountId);
        if (a.getStatus() == SocialAccount.Status.NEEDS_RECONNECT) {
            throw new SocialException(Kind.RECONNECT, a.getDisplayName() + " needs to be reconnected"
                    + (a.getLastError() != null ? ": " + a.getLastError() : "."));
        }
        boolean refreshable = a.getPlatform() == SocialPlatform.YOUTUBE || a.getPlatform() == SocialPlatform.TIKTOK;
        if (!refreshable || a.getAccessExpiresAt() == null || a.getAccessExpiresAt().isAfter(Instant.now().plus(REFRESH_MARGIN))) {
            return tokens(accountId).accessToken();
        }
        synchronized (refreshLocks.computeIfAbsent(accountId, k -> new Object())) {
            a = find(accountId);   // another thread may have refreshed already
            if (a.getAccessExpiresAt() != null && a.getAccessExpiresAt().isAfter(Instant.now().plus(REFRESH_MARGIN))) {
                return tokens(accountId).accessToken();
            }
            String refresh = tokens(accountId).refreshToken();
            try {
                TokenSet t = a.getPlatform() == SocialPlatform.YOUTUBE ? youtube.refresh(refresh) : tiktok.refresh(refresh);
                vault.store(accountId, t.accessToken(), t.refreshToken());
                updateExpiry(accountId, t);
                return t.accessToken();
            } catch (SocialException e) {
                if (e.kind() == Kind.RECONNECT) flagReconnect(accountId, e.getMessage());
                throw e;
            }
        }
    }

    private TokenVault.Tokens tokens(UUID accountId) {
        try {
            return vault.load(accountId).orElseThrow(() -> new SocialException(Kind.RECONNECT, "No saved sign-in. Reconnect the account."));
        } catch (SocialException e) {
            if (e.kind() == Kind.RECONNECT) flagReconnect(accountId, e.getMessage());
            throw e;
        }
    }

    @Transactional
    public void updateExpiry(UUID accountId, TokenSet t) {
        accounts.findById(accountId).ifPresent(a -> {
            a.setAccessExpiresAt(t.accessExpiresAt());
            if (t.refreshExpiresAt() != null) a.setRefreshExpiresAt(t.refreshExpiresAt());
            accounts.save(a);
        });
    }

    @Transactional
    public void flagReconnect(UUID accountId, String reason) {
        accounts.findById(accountId).ifPresent(a -> {
            a.markNeedsReconnect(reason);
            accounts.save(a);
        });
    }

    /** Re-reads the profile to check the sign-in still works. */
    public AccountResponse check(UUID id) {
        SocialAccount a = find(id);
        try {
            String token = accessToken(id);
            Profile p = switch (a.getPlatform()) {
                case YOUTUBE -> youtube.channel(token);
                case TIKTOK -> tiktok.user(token, a.getExternalId());
                case FACEBOOK, INSTAGRAM -> null;
            };
            if (a.getPlatform() == SocialPlatform.INSTAGRAM) meta.igPublishingLimit(a.getExternalId(), token);
            if (a.getPlatform() == SocialPlatform.FACEBOOK) meta.pageName(a.getExternalId(), token);
            return markChecked(id, p);
        } catch (SocialException e) {
            if (e.kind() == Kind.RECONNECT) flagReconnect(id, e.getMessage());
            if (e.kind() == Kind.RECONNECT || e.kind() == Kind.NOT_CONFIGURED) return AccountResponse.of(find(id));
            throw new ApiException(e.kind() == Kind.RATE_LIMITED ? ErrorCode.RATE_LIMIT_EXCEEDED : ErrorCode.CONFLICT, e.getMessage());
        }
    }

    @Transactional
    AccountResponse markChecked(UUID id, Profile p) {
        SocialAccount a = find(id);
        if (p != null) {
            a.setDisplayName(p.displayName());
            if (p.username() != null) a.setUsername(p.username());
            a.setAvatarUrl(p.avatarUrl());
        }
        if (a.getStatus() == SocialAccount.Status.CONNECTED) a.markConnected();
        return AccountResponse.of(accounts.save(a));
    }

    /** TikTok's creator info, which must be shown on the post screen every time. */
    public CreatorInfoResponse tiktokCreator(UUID id) {
        SocialAccount a = find(id);
        if (a.getPlatform() != SocialPlatform.TIKTOK) throw ApiException.badRequest("Not a TikTok account");
        try {
            TikTokClient.CreatorInfo c = tiktok.creatorInfo(accessToken(id));
            if (c.username() != null && !c.username().equals(a.getUsername())) rememberUsername(id, c.username());
            return CreatorInfoResponse.of(c);
        } catch (SocialException e) {
            throw new ApiException(e.kind() == Kind.RATE_LIMITED ? ErrorCode.RATE_LIMIT_EXCEEDED : ErrorCode.CONFLICT, e.getMessage());
        }
    }

    @Transactional
    public void rememberUsername(UUID id, String username) {
        accounts.findById(id).ifPresent(a -> {
            a.setUsername(username);
            accounts.save(a);
        });
    }

    // ---- disconnect -------------------------------------------------------------

    @Transactional
    public void disconnect(UUID id) {
        SocialAccount a = find(id);
        try {
            TokenVault.Tokens t = vault.load(id).orElse(null);
            if (t != null) {
                switch (a.getPlatform()) {
                    case YOUTUBE -> youtube.revoke(t.refreshToken() != null ? t.refreshToken() : t.accessToken());
                    case TIKTOK -> tiktok.revoke(t.accessToken());
                    // One Meta sign-in covers every Page and Instagram account, so it is not revoked
                    // here; removing the app in Facebook settings revokes it for all of them.
                    case FACEBOOK, INSTAGRAM -> { }
                }
            }
        } catch (SocialException ignored) {
            // tokens unreadable: nothing to revoke
        }
        jdbc.update("""
                UPDATE post_targets SET status = 'FAILED', error_message = 'The account was disconnected', locked_until = NULL
                WHERE account_id = ? AND status IN ('SCHEDULED', 'DRAFT')
                """, id);
        vault.delete(id);
        accounts.delete(a);
        audit.record("SOCIAL_ACCOUNT_DISCONNECTED", "social_account", id,
                Map.of("platform", a.getPlatform().name(), "name", a.getDisplayName()));
    }
}
