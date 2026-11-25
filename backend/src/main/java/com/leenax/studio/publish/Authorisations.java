package com.leenax.studio.publish;

import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.common.SqlStates;
import com.leenax.studio.social.SocialPlatform;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Authorisation records (E9): "no publishing without an explicit authorisation record, and no
 * public post without confirmation." A person authorises exactly one target: its file and its
 * settings (the database stores their hash). Nothing starts uploading without a matching record,
 * which the database checks too.
 */
@Component
public class Authorisations {

    static final String INSERT = "insert into publish_authorisations (id, post_target_id, media_id, settings_hash, platform, public,"
            + " public_confirmed, rights_confirmed, project_id, resource_id, version_id, statement)"
            + " values (?, ?, '00000000-0000-0000-0000-000000000000', '', '', false, ?, ?, ?, null, ?, ?)";

    static final String MATCHES = "select count(*) from publish_authorisations a join post_targets t on t.id = a.post_target_id"
            + " join posts p on p.id = t.post_id where a.post_target_id = ? and a.settings_hash = leenax_settings_hash(t.settings)"
            + " and a.media_id = p.media_id";

    private final JdbcTemplate jdbc;

    public Authorisations(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** For a lesson or quiz video: which project and approved version it is. */
    public record Education(UUID projectId, UUID versionId, boolean rightsConfirmed) {
    }

    /**
     * Records the signed-in person's authorisation of a target. The database fills in the file,
     * the settings hash and whether it's public, and refuses a public post without confirmation.
     */
    public UUID authorise(UUID targetId, boolean publicConfirmed, Education education, String statement) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update(INSERT, id, targetId, publicConfirmed, education != null && education.rightsConfirmed(),
                    education == null ? null : education.projectId(), education == null ? null : education.versionId(), statement);
        } catch (RuntimeException e) {
            throw SqlStates.translate(e).orElseThrow(() -> e);
        }
        return id;
    }

    /** Whether the target's current file and settings are authorised. */
    public boolean authorised(UUID targetId) {
        Integer n = jdbc.queryForObject(MATCHES, Integer.class, targetId);
        return n != null && n > 0;
    }

    /** Mirrors leenax_target_is_public: what would be seen by anyone. */
    public static boolean isPublic(SocialPlatform platform, Map<String, Object> settings) {
        Object privacy = settings == null ? null : settings.get("privacy");
        return switch (platform) {
            case YOUTUBE -> "public".equals(privacy) || (settings.get("publishAt") != null && !settings.get("publishAt").toString().isBlank());
            case TIKTOK -> !"SELF_ONLY".equals(privacy);
            default -> true;
        };
    }

    public static String statement(SocialPlatform platform, String account, Map<String, Object> settings, boolean isPublic) {
        Object title = settings.get("title");
        return "Authorised posting " + (title == null || title.toString().isBlank() ? "this video" : "“" + title + "”") + " to "
                + platform.displayName() + " (" + account + ")" + (settings.get("publishAt") != null ? ", private until it goes public at " + settings.get("publishAt") : isPublic ? ", publicly" : ", privacy " + settings.getOrDefault("privacy", "as set")) + ".";
    }

    /** Whether a post was made from Publish in a project (it's edited there, not in the creator's editor). */
    public boolean education(UUID postId) {
        Integer n = jdbc.queryForObject("select count(*) from youtube_publications where post_id = ?", Integer.class, postId);
        return n != null && n > 0;
    }

    public static ApiException notConfirmed() {
        return new ApiException(ErrorCode.INVALID_REQUEST, "Confirm that this will be posted publicly");
    }
}
