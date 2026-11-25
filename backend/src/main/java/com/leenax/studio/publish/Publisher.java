package com.leenax.studio.publish;

import com.leenax.studio.social.SocialPlatform;

/**
 * Posts a video to one platform. {@link #start} uploads and submits; {@link #poll}
 * follows the platform's processing until the post is live. Both keep their
 * progress in {@link Job#state()} and call {@link Job#checkpoint} after every
 * irreversible step, so a retry after a crash never posts twice.
 */
public interface Publisher {

    SocialPlatform platform();

    Outcome start(Job job);

    Outcome poll(Job job);
}
