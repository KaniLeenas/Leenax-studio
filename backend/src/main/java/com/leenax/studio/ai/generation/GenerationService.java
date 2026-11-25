package com.leenax.studio.ai.generation;

import com.leenax.studio.ai.accounts.AccountService;
import com.leenax.studio.ai.accounts.AiAccount;
import com.leenax.studio.ai.credits.CreditService;
import com.leenax.studio.ai.generation.GenerationDtos.CreateResponse;
import com.leenax.studio.ai.generation.GenerationDtos.EstimateResponse;
import com.leenax.studio.ai.generation.GenerationDtos.JobResponse;
import com.leenax.studio.ai.generation.GenerationDtos.VideoGenerationRequest;
import com.leenax.studio.ai.models.AiModel;
import com.leenax.studio.ai.models.AiModelRepository;
import com.leenax.studio.ai.models.ModelOptions;
import com.leenax.studio.ai.provider.ProviderCredentials;
import com.leenax.studio.ai.provider.ProviderRegistry;
import com.leenax.studio.ai.provider.VideoProvider;
import com.leenax.studio.ai.provider.VideoRequest;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.media.MediaKind;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.projects.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GenerationService {

    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);

    private final GenerationJobRepository jobs;
    private final AiModelRepository models;
    private final AccountService accounts;
    private final CreditService credits;
    private final MediaService media;
    private final ProjectRepository projects;
    private final ProviderRegistry registry;
    private final JobMapper mapper;
    private final GenerationEvents events;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public GenerationService(GenerationJobRepository jobs, AiModelRepository models, AccountService accounts,
                             CreditService credits, MediaService media, ProjectRepository projects,
                             ProviderRegistry registry, JobMapper mapper, GenerationEvents events,
                             AuditService audit, TransactionTemplate tx) {
        this.jobs = jobs;
        this.models = models;
        this.accounts = accounts;
        this.credits = credits;
        this.media = media;
        this.projects = projects;
        this.registry = registry;
        this.mapper = mapper;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
    }

    /** Everything a request needs, checked before anything is sent to (or billed by) a provider. */
    record Validated(AiAccount account, AiModel model, ModelOptions options, List<String> warnings) {
    }

    Validated validate(VideoGenerationRequest r) {
        AiAccount account = accounts.get(r.accountId());
        if (account.getStatus() == AiAccount.Status.DISABLED) {
            throw new ApiException(ErrorCode.CONFLICT, "“" + account.getName() + "” is switched off");
        }
        if (account.getStatus() == AiAccount.Status.INVALID) {
            throw new ApiException(ErrorCode.CONFLICT, "The key for “" + account.getName() + "” was rejected. Replace it first.");
        }
        AiModel model = models.findById(r.modelId()).orElseThrow(() -> ApiException.notFound("Model"));
        if (!model.isEnabled()) throw ApiException.badRequest(model.getDisplayName() + " is switched off");
        if (model.getCapability() != AiModel.Capability.VIDEO) throw ApiException.badRequest("That is not a video model");
        if (model.getProvider() != account.getProvider()) {
            throw ApiException.badRequest(model.getDisplayName() + " needs a " + model.getProvider().displayName() + " account");
        }
        if (registry.video(model.getProvider()).isEmpty()) {
            throw ApiException.badRequest(model.getProvider().displayName() + " video is not supported yet");
        }

        ModelOptions o = new ModelOptions(model.getOptions());
        if (!o.supports(r.mode())) {
            throw ApiException.badRequest(model.getDisplayName() + " does not support "
                    + (r.mode() == VideoRequest.Mode.TEXT_TO_VIDEO ? "text-to-video" : "image-to-video"));
        }
        String durationError = o.validateDuration(r.durationSeconds());
        if (durationError != null) throw ApiException.badRequest(durationError);
        List<String> ratios = o.aspectRatios(r.mode());
        if (r.aspectRatio() != null && !ratios.isEmpty() && !ratios.contains(r.aspectRatio())) {
            throw ApiException.badRequest("Choose one of the listed formats for " + model.getDisplayName());
        }
        List<String> resolutions = o.resolutions();
        if (r.resolution() != null && !resolutions.isEmpty() && !resolutions.contains(r.resolution())) {
            throw ApiException.badRequest("Choose one of the listed resolutions for " + model.getDisplayName());
        }
        if (Boolean.TRUE.equals(r.audio()) && !o.supportsAudio()) {
            throw ApiException.badRequest(model.getDisplayName() + " cannot generate sound");
        }
        List<String> warnings = new ArrayList<>();
        if (r.mode() == VideoRequest.Mode.IMAGE_TO_VIDEO) {
            if (r.imageMediaId() == null) throw ApiException.badRequest("Choose a start image");
            var img = media.findResponse(r.imageMediaId()).orElseThrow(() -> ApiException.notFound("Image"));
            if (img.kind() != MediaKind.IMAGE) throw ApiException.badRequest("The start frame must be an image");
            if (o.needsPublicImageUrl()) {
                warnings.add(model.getProvider().displayName()
                        + " downloads the start image from a link, so this only works when your studio is online.");
            }
        }
        if (r.projectId() != null && !projects.existsById(r.projectId())) throw ApiException.notFound("Project");
        if (account.getStatus() == AiAccount.Status.UNVERIFIED) {
            warnings.add("“" + account.getName() + "” has not been verified yet. If the key is wrong the generation will fail.");
        }
        return new Validated(account, model, o, warnings);
    }

    public EstimateResponse estimate(VideoGenerationRequest r) {
        return estimate(r, validate(r));
    }

    EstimateResponse estimate(VideoGenerationRequest r, Validated v) {
        List<String> warnings = new ArrayList<>(v.warnings());
        Integer seconds = r.durationSeconds() != null ? r.durationSeconds() : v.options().defaultDuration();
        CostEstimator.Estimate one = CostEstimator.estimate(v.model().getPricing(), seconds, r.resolution(), r.audio());
        int n = r.variationCount();

        var rows = credits.forAccount(v.account().getId());
        Instant now = Instant.now();
        var totals = CreditService.totals(rows, now);
        CreditService.Total balance = null;
        if (one != null) {
            balance = totals.stream().filter(t -> t.unit().equals(one.unit())).findFirst().orElse(null);
        } else if (!totals.isEmpty()) {
            balance = totals.get(0);
        }

        BigDecimal total = one == null ? null : one.amount().multiply(BigDecimal.valueOf(n));
        BigDecimal after = balance != null && total != null ? balance.amount().subtract(total) : null;
        boolean insufficient = after != null && after.signum() < 0;
        BigDecimal threshold = v.account().getLowCreditThreshold();
        boolean low = threshold != null && balance != null
                && (after != null ? after : balance.amount()).compareTo(threshold) < 0;

        if (one == null) {
            warnings.add("No price is set for " + v.model().getDisplayName()
                    + ". Add one under Credits & usage to see estimates.");
        }
        if (insufficient) warnings.add("This looks like more than the recorded balance. The provider may refuse it.");
        else if (low) warnings.add("You'll be under your low-credit warning level after this.");
        if (rows.stream().anyMatch(b -> CreditService.expiringSoon(b, now))) {
            warnings.add("Some credits on this account expire within 7 days.");
        }

        return new EstimateResponse(one == null ? null : one.amount(), total, one == null ? null : one.unit(), n,
                balance == null ? null : balance.amount(), balance == null ? null : balance.unit(), after,
                low, insufficient, warnings);
    }

    public CreateResponse create(VideoGenerationRequest r, String idempotencyKey) {
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.strip();
        if (key != null && key.length() > 100) throw ApiException.badRequest("Idempotency-Key is too long");
        if (key != null) {
            List<GenerationJob> existing = jobs.findByIdempotencyKeyOrderByVariationIndexAsc(key);
            if (!existing.isEmpty()) {
                return new CreateResponse(existing.stream().map(mapper::toResponse).toList(), null);
            }
        }

        Validated v = validate(r);
        EstimateResponse estimate = estimate(r, v);
        Integer seconds = r.durationSeconds() != null ? r.durationSeconds() : v.options().defaultDuration();

        Map<String, Object> params = new LinkedHashMap<>();
        if (seconds != null) params.put("durationSeconds", seconds);
        if (r.aspectRatio() != null) params.put("aspectRatio", r.aspectRatio());
        if (r.resolution() != null) params.put("resolution", r.resolution());
        if (r.audio() != null && v.options().supportsAudio()) params.put("audio", r.audio());

        UUID group = UUID.randomUUID();
        List<GenerationJob> created;
        try {
            created = tx.execute(s -> {
                List<GenerationJob> list = new ArrayList<>();
                for (int i = 0; i < r.variationCount(); i++) {
                    GenerationJob j = new GenerationJob(group, i);
                    j.setAccountId(v.account().getId());
                    j.setAccountName(v.account().getName());
                    j.setProvider(v.model().getProvider());
                    j.setModelId(v.model().getId());
                    j.setModelCode(v.model().getModelCode());
                    j.setModelName(v.model().getDisplayName());
                    j.setMode(r.mode());
                    j.setPrompt(r.prompt().strip());
                    j.setNegativePrompt(r.negativePrompt() == null || r.negativePrompt().isBlank() ? null : r.negativePrompt().strip());
                    j.setParams(params);
                    j.setInputMediaId(r.mode() == VideoRequest.Mode.IMAGE_TO_VIDEO ? r.imageMediaId() : null);
                    j.setProjectId(r.projectId());
                    j.setEstimatedCost(estimate.perVideo());
                    j.setCostUnit(estimate.unit());
                    j.setIdempotencyKey(key);
                    list.add(jobs.save(j));
                }
                return list;
            });
        } catch (DataIntegrityViolationException e) {
            // Same Idempotency-Key raced in from a double click: return the winner.
            if (key == null) throw e;
            return new CreateResponse(jobs.findByIdempotencyKeyOrderByVariationIndexAsc(key).stream()
                    .map(mapper::toResponse).toList(), estimate);
        }
        audit.record("VIDEO_GENERATION_QUEUED", "generation_group", group,
                Map.of("provider", v.model().getProvider().name(), "model", v.model().getModelCode(), "count", created.size()));
        created.forEach(events::publish);
        return new CreateResponse(created.stream().map(mapper::toResponse).toList(), estimate);
    }

    public Page<JobResponse> list(boolean activeOnly, int page, int size) {
        PageRequest p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 60));
        Page<GenerationJob> result = activeOnly
                ? jobs.findByStatusInOrderByCreatedAtDescVariationIndexAsc(EnumSet.of(GenerationJob.Status.QUEUED, GenerationJob.Status.PROCESSING), p)
                : jobs.findAllByOrderByCreatedAtDescVariationIndexAsc(p);
        return result.map(mapper::toResponse);
    }

    public JobResponse get(UUID id) {
        return mapper.toResponse(find(id));
    }

    public JobResponse cancel(UUID id) {
        GenerationJob job = find(id);
        if (job.getStatus().terminal()) {
            throw new ApiException(ErrorCode.CONFLICT, "This generation has already finished");
        }
        if (job.getStatus() == GenerationJob.Status.PROCESSING && job.getProviderJobId() != null && job.getAccountId() != null) {
            try {
                VideoProvider p = registry.video(job.getProvider()).orElseThrow();
                ProviderCredentials creds = accounts.forUse(job.getAccountId()).credentials();
                p.cancel(job.getProviderJobId(), creds);
            } catch (RuntimeException e) {
                log.info("Provider cancel for job {} failed: {}", id, e.getMessage());
            }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                GenerationJob fresh = find(id);
                if (fresh.getStatus().terminal()) return mapper.toResponse(fresh);
                fresh.setStatus(GenerationJob.Status.CANCELLED);
                fresh.setCompletedAt(Instant.now());
                fresh.setLockedUntil(null);
                GenerationJob saved = jobs.save(fresh);
                events.publish(saved);
                return mapper.toResponse(saved);
            } catch (ObjectOptimisticLockingFailureException e) {
                // the worker updated it at the same moment; try again
            }
        }
        throw new ApiException(ErrorCode.CONFLICT, "Could not cancel right now. Try again.");
    }

    /** Runs the same request again as a new generation. */
    public CreateResponse regenerate(UUID id) {
        GenerationJob j = find(id);
        if (j.getAccountId() == null || j.getModelId() == null) {
            throw new ApiException(ErrorCode.CONFLICT, "The account or model used for this video no longer exists");
        }
        VideoGenerationRequest r = new VideoGenerationRequest(j.getAccountId(), j.getModelId(), j.getMode(), j.getPrompt(),
                j.getNegativePrompt(), j.durationSeconds(), j.aspectRatio(), j.resolution(), j.audio(),
                j.getInputMediaId(), j.getProjectId(), 1);
        return create(r, null);
    }

    public void delete(UUID id) {
        GenerationJob j = find(id);
        if (!j.getStatus().terminal()) {
            throw new ApiException(ErrorCode.CONFLICT, "Cancel the generation before removing it");
        }
        jobs.delete(j);
    }

    private GenerationJob find(UUID id) {
        return jobs.findById(id).orElseThrow(() -> ApiException.notFound("Generation"));
    }
}
