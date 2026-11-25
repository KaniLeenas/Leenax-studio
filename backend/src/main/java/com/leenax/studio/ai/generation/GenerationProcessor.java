package com.leenax.studio.ai.generation;

import com.leenax.studio.ai.accounts.AccountService;
import com.leenax.studio.ai.credits.CreditService;
import com.leenax.studio.ai.credits.UsageLog;
import com.leenax.studio.ai.provider.ProviderCredentials;
import com.leenax.studio.ai.provider.ProviderErrorKind;
import com.leenax.studio.ai.provider.ProviderException;
import com.leenax.studio.ai.provider.ProviderRegistry;
import com.leenax.studio.ai.provider.VideoProvider;
import com.leenax.studio.ai.provider.VideoRequest;
import com.leenax.studio.ai.provider.VideoResult;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.media.MediaAsset;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.media.MediaSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Moves one job forward by one step: submit → poll → download and save.
 * Called by {@link GenerationWorker} while it holds the job's lease. Network
 * calls run outside transactions; @Version stops a stale write from
 * overwriting a cancel made in the meantime.
 */
@Component
public class GenerationProcessor {

    private static final Logger log = LoggerFactory.getLogger(GenerationProcessor.class);
    static final int MAX_ATTEMPTS = 5;
    static final Duration MAX_RUNTIME = Duration.ofMinutes(75);

    private final GenerationJobRepository jobs;
    private final AccountService accounts;
    private final CreditService credits;
    private final MediaService media;
    private final ProviderRegistry registry;
    private final GenerationEvents events;

    public GenerationProcessor(GenerationJobRepository jobs, AccountService accounts, CreditService credits,
                               MediaService media, ProviderRegistry registry, GenerationEvents events) {
        this.jobs = jobs;
        this.accounts = accounts;
        this.credits = credits;
        this.media = media;
        this.registry = registry;
        this.events = events;
    }

    public void process(UUID id) {
        GenerationJob job = jobs.findById(id).orElse(null);
        if (job == null) return;
        if (job.getStatus().terminal()) {
            release(job);
            return;
        }
        try {
            if (job.getAccountId() == null) {
                fail(job, "PROVIDER_CONFIGURATION_ERROR", "The account for this generation was deleted");
                return;
            }
            VideoProvider provider = registry.video(job.getProvider()).orElseThrow(() ->
                    new ProviderException(ProviderErrorKind.UNSUPPORTED_CAPABILITY, job.getProvider().displayName() + " video is not supported"));
            ProviderCredentials creds = accounts.forUse(job.getAccountId()).credentials();

            if (job.getStatus() == GenerationJob.Status.QUEUED) {
                submit(job, provider, creds);
            } else {
                poll(job, provider, creds);
            }
        } catch (ProviderException e) {
            handleProviderError(job, e);
        } catch (ApiException e) {
            fail(job, "PROVIDER_CONFIGURATION_ERROR", e.getMessage());
        } catch (ObjectOptimisticLockingFailureException e) {
            log.debug("Job {} changed while processing (probably cancelled)", id);
        } catch (RuntimeException e) {
            log.error("Unexpected error processing job {}", id, e);
            handleProviderError(job, new ProviderException(ProviderErrorKind.PROVIDER_UNAVAILABLE, "Unexpected error; will retry"));
        }
    }

    // ---- steps ---------------------------------------------------------------

    private void submit(GenerationJob job, VideoProvider provider, ProviderCredentials creds) {
        VideoRequest.InputImage image = null;
        if (job.getMode() == VideoRequest.Mode.IMAGE_TO_VIDEO) {
            if (job.getInputMediaId() == null) {
                fail(job, "INVALID_REQUEST", "The start image was deleted");
                return;
            }
            var src = media.sourceImage(job.getInputMediaId());
            image = new VideoRequest.InputImage(src.bytes(), src.contentType(), src.signedUrl());
        }
        VideoRequest request = new VideoRequest(job.getId().toString(), job.getMode(), job.getModelCode(), job.getPrompt(),
                job.getNegativePrompt(), job.durationSeconds(), job.aspectRatio(), job.resolution(), job.audio(), image);

        VideoResult result = provider.submit(request, creds);

        job.setProviderJobId(result.providerJobId());
        job.setSubmittedAt(Instant.now());
        job.setStatus(GenerationJob.Status.PROCESSING);
        job.clearError();
        if (result.cost() != null) {           // provider's own estimate beats ours
            job.setEstimatedCost(result.cost());
            job.setCostUnit(result.costUnit());
        }
        applyResult(job, result, provider, creds);
    }

    private void poll(GenerationJob job, VideoProvider provider, ProviderCredentials creds) {
        if (job.getSubmittedAt() != null && job.getSubmittedAt().plus(MAX_RUNTIME).isBefore(Instant.now())) {
            try {
                provider.cancel(job.getProviderJobId(), creds);
            } catch (RuntimeException ignored) {
                // best effort
            }
            fail(job, ProviderErrorKind.GENERATION_TIMEOUT.name(),
                    job.getProvider().displayName() + " did not finish within " + MAX_RUNTIME.toMinutes() + " minutes");
            return;
        }
        VideoResult result = provider.poll(job.getProviderJobId(), creds);
        job.clearError();
        applyResult(job, result, provider, creds);
    }

    private void applyResult(GenerationJob job, VideoResult result, VideoProvider provider, ProviderCredentials creds) {
        switch (result.state()) {
            case RUNNING -> {
                if (result.progress() != null) job.setProgress(result.progress());
                job.scheduleNext(provider.pollIntervalSeconds());
                publish(jobs.save(job));
            }
            case SUCCEEDED -> finish(job, result, provider, creds);
            case FAILED -> {
                ProviderErrorKind kind = result.errorKind() != null ? result.errorKind() : ProviderErrorKind.GENERATION_FAILED;
                fail(job, kind.name(), result.errorMessage());
            }
            case CANCELLED -> {
                job.setStatus(GenerationJob.Status.CANCELLED);
                job.setCompletedAt(Instant.now());
                job.setLockedUntil(null);
                publish(jobs.save(job));
            }
        }
    }

    private void finish(GenerationJob job, VideoResult result, VideoProvider provider, ProviderCredentials creds) {
        job.setProgress(0.99);
        Path temp = null;
        MediaAsset asset;
        try {
            temp = Files.createTempFile("leenax-gen-", ".mp4");
            provider.download(result.output(), creds, temp);
            asset = media.ingest(temp, outputName(job), MediaSource.AI_GENERATED, job.getProjectId());
        } catch (java.io.IOException e) {
            throw new ProviderException(ProviderErrorKind.PROVIDER_UNAVAILABLE, "Could not create a temporary file");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (java.io.IOException ignored) {
                    // temp file
                }
            }
        }

        UsageLog.CostSource source;
        BigDecimal cost;
        String unit;
        if (result.cost() != null) {
            source = UsageLog.CostSource.PROVIDER_REPORTED;
            cost = result.cost();
            unit = result.costUnit();
        } else if (job.getEstimatedCost() != null) {
            source = UsageLog.CostSource.ESTIMATED;
            cost = job.getEstimatedCost();
            unit = job.getCostUnit();
        } else {
            source = UsageLog.CostSource.UNKNOWN;
            cost = null;
            unit = null;
        }

        job.setOutputMediaId(asset.getId());
        job.setActualCost(cost);
        if (unit != null) job.setCostUnit(unit);
        job.setStatus(GenerationJob.Status.COMPLETED);
        job.setProgress(1.0);
        job.setCompletedAt(Instant.now());
        job.setLockedUntil(null);
        GenerationJob saved = jobs.save(job);

        BigDecimal seconds = result.outputSeconds() != null ? BigDecimal.valueOf(result.outputSeconds())
                : asset.getDurationMs() != null ? BigDecimal.valueOf(asset.getDurationMs()).movePointLeft(3)
                : job.durationSeconds() != null ? BigDecimal.valueOf(job.durationSeconds()) : null;
        credits.recordUsage(new UsageLog(job.getAccountId(), job.getAccountName(), job.getProvider(), job.getModelCode(),
                "VIDEO", job.getId(), seconds, cost, unit, source, true));
        credits.deductManual(job.getAccountId(), cost, unit);
        publish(saved);
        accounts.syncBalancesQuietly(job.getAccountId(), creds);
    }

    // ---- failures ------------------------------------------------------------

    private void handleProviderError(GenerationJob job, ProviderException e) {
        if (e.kind() == ProviderErrorKind.INVALID_CREDENTIALS && job.getAccountId() != null) {
            accounts.markInvalid(job.getAccountId(), e.getMessage());
        }
        if (e.retryable() && job.getAttempts() + 1 < MAX_ATTEMPTS) {
            int delay = backoffSeconds(job.getAttempts());
            job.noteRetry(e.getMessage() + " Retrying in " + delay + " s.", delay);
            safeSave(job);
            return;
        }
        fail(job, e.kind().name(), e.getMessage());
    }

    static int backoffSeconds(int attempts) {
        return (int) Math.min(15L * (1L << Math.min(attempts, 5)), 300);
    }

    private void fail(GenerationJob job, String code, String message) {
        boolean wasSubmitted = job.getProviderJobId() != null;
        job.fail(code, message);
        GenerationJob saved = safeSave(job);
        if (saved != null && wasSubmitted) {
            credits.recordUsage(new UsageLog(job.getAccountId(), job.getAccountName(), job.getProvider(), job.getModelCode(),
                    "VIDEO", job.getId(), null, null, null, UsageLog.CostSource.UNKNOWN, false));
        }
    }

    private void release(GenerationJob job) {
        job.setLockedUntil(null);
        safeSave(job);
    }

    private GenerationJob safeSave(GenerationJob job) {
        try {
            GenerationJob saved = jobs.save(job);
            publish(saved);
            return saved;
        } catch (ObjectOptimisticLockingFailureException e) {
            return null; // cancelled or changed elsewhere; that state wins
        }
    }

    private void publish(GenerationJob job) {
        events.publish(job);
    }

    /** "madurai-street-food-at-night-v2.mp4" */
    static String outputName(GenerationJob job) {
        String base = job.getPrompt().toLowerCase()
                .replaceAll("[^\\p{L}\\p{M}\\p{N}]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (base.length() > 48) base = base.substring(0, 48).replaceAll("-+$", "");
        if (base.isBlank()) base = "ai-video";
        return base + (job.getVariationIndex() > 0 ? "-v" + (job.getVariationIndex() + 1) : "") + ".mp4";
    }
}
