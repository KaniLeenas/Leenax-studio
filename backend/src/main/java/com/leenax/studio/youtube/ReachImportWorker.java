package com.leenax.studio.youtube;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Imports YouTube's daily reach files (impressions and click-through rate) once a day. */
@Component
public class ReachImportWorker {

    private final YouTubeAnalyticsService analytics;

    public ReachImportWorker(YouTubeAnalyticsService analytics) {
        this.analytics = analytics;
    }

    /** 14:20 UTC: after YouTube's files for the previous day are usually ready. */
    @Scheduled(cron = "${leenax.youtube.reach-import-cron:0 20 14 * * *}", zone = "UTC")
    public void daily() {
        analytics.importAll();
    }
}
