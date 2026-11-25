package com.leenax.studio.images;

import com.leenax.studio.ai.accounts.AccountService;
import com.leenax.studio.ai.credits.UsageRecorder;
import com.leenax.studio.ai.provider.ImageProvider;
import com.leenax.studio.ai.provider.MediaTypes.Clip;
import com.leenax.studio.ai.provider.MediaTypes.ImageRequest;
import com.leenax.studio.ai.provider.MediaTypes.Sample;
import com.leenax.studio.ai.provider.ProviderException;
import com.leenax.studio.ai.provider.ProviderRegistry;
import com.leenax.studio.audio.Ffmpeg;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.TempFiles;
import com.leenax.studio.images.ImageDtos.GenerateRequest;
import com.leenax.studio.images.ImageDtos.ImagesResult;
import com.leenax.studio.images.ImageDtos.ResizeRequest;
import com.leenax.studio.media.MediaAsset;
import com.leenax.studio.media.MediaKind;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.media.MediaSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** AI images (OpenAI GPT Image, Google Nano Banana), background removal and resizing. All results go to the media library. */
@Service
public class ImageService {

    static final long MAX_REFERENCE_BYTES = 20L * 1024 * 1024;

    private final AccountService accounts;
    private final ProviderRegistry registry;
    private final MediaService media;
    private final Ffmpeg ffmpeg;
    private final BackgroundRemover remover;
    private final UsageRecorder usage;

    public ImageService(AccountService accounts, ProviderRegistry registry, MediaService media, Ffmpeg ffmpeg,
                        BackgroundRemover remover, UsageRecorder usage) {
        this.accounts = accounts;
        this.registry = registry;
        this.media = media;
        this.ffmpeg = ffmpeg;
        this.remover = remover;
        this.usage = usage;
    }

    public ImagesResult generate(GenerateRequest req) {
        var usable = accounts.forUse(req.accountId());
        ImageProvider provider = registry.image(usable.account().getProvider()).orElseThrow(() ->
                ApiException.badRequest(usable.account().getProvider().displayName() + " cannot make images. Use an OpenAI or Google Gemini account."));
        if (req.size() != null) validateOpenAiSize(req.size());

        List<Sample> refs = new ArrayList<>();
        if (req.references() != null) {
            for (UUID id : req.references()) {
                refs.add(media.sample(id, Set.of(MediaKind.IMAGE), MAX_REFERENCE_BYTES, "References must be images"));
            }
        }
        int count = req.count() == null ? 1 : req.count();
        ImageRequest request = new ImageRequest(req.model().strip(), req.prompt().strip(), req.size(), req.aspectRatio(),
                req.imageSize(), count, req.quality(), req.transparent(), refs);
        List<Clip> clips;
        try {
            clips = provider.generate(request, usable.credentials());
        } catch (ProviderException e) {
            usage.record(usable.account(), req.model(), "IMAGE", null, null, null, false, false);
            throw e;
        }
        if (clips.isEmpty()) throw ApiException.badRequest("The provider returned no image. Try rewording the prompt.");
        usage.record(usable.account(), req.model(), "IMAGE", null, clips.size(), "IMAGES", false, true);

        String name = req.prompt().strip().replaceAll("\\s+", " ");
        if (name.length() > 40) name = name.substring(0, 40).strip() + "…";
        List<com.leenax.studio.media.MediaDtos.MediaResponse> out = new ArrayList<>();
        try (TempFiles tmp = new TempFiles()) {
            int i = 1;
            for (Clip c : clips) {
                String ext = TempFiles.extensionFor(c.mimeType());
                MediaAsset asset = media.ingest(tmp.write(c.bytes(), ext), name + (clips.size() > 1 ? " " + i : "") + ext,
                        MediaSource.AI_GENERATED, req.projectId());
                out.add(media.toResponse(asset));
                i++;
            }
        }
        return new ImagesResult(out);
    }

    /** GPT Image rules: both sides multiples of 16, ratio between 1:3 and 3:1, at most 3840×2160 pixels' worth. */
    static void validateOpenAiSize(String size) {
        String[] p = size.split("x");
        int w = Integer.parseInt(p[0]);
        int h = Integer.parseInt(p[1]);
        if (w % 16 != 0 || h % 16 != 0) throw ApiException.badRequest("Width and height must be multiples of 16");
        double ratio = (double) Math.max(w, h) / Math.min(w, h);
        if (ratio > 3.0) throw ApiException.badRequest("The image can be at most 3 times wider than tall (or the reverse)");
        if (Math.max(w, h) > 3840 || Math.min(w, h) > 2160) throw ApiException.badRequest("The largest size is 3840×2160");
    }

    public ImagesResult removeBackground(UUID mediaId) {
        Sample s = media.sample(mediaId, Set.of(MediaKind.IMAGE), MAX_REFERENCE_BYTES, "Pick an image");
        byte[] png = remover.removeBackground(s.bytes(), s.filename(), s.mimeType());
        MediaAsset source = media.findAsset(mediaId);
        try (TempFiles tmp = new TempFiles()) {
            MediaAsset asset = media.ingest(tmp.write(png, ".png"), base(source.getOriginalFilename()) + " (no background).png",
                    MediaSource.AI_GENERATED, source.getProjectId());
            return new ImagesResult(List.of(media.toResponse(asset)));
        }
    }

    public ImagesResult resize(ResizeRequest req) {
        try (TempFiles tmp = new TempFiles()) {
            var in = media.localCopy(req.mediaId(), Set.of(MediaKind.IMAGE, MediaKind.VIDEO), "Pick an image");
            tmp.track(in.path());
            String ext = "jpg".equals(req.format()) ? ".jpg" : ".png";
            var out = tmp.create(ext);
            ffmpeg.run(ImageRecipes.resize(in.path(), out, req.width(), req.height(), req.fit(), req.background()));
            MediaAsset asset = media.ingest(out, base(in.asset().getOriginalFilename()) + " " + req.width() + "x" + req.height() + ext,
                    MediaSource.AI_GENERATED, in.asset().getProjectId());
            return new ImagesResult(List.of(media.toResponse(asset)));
        }
    }

    public boolean backgroundRemovalAvailable() {
        return remover.configured();
    }

    private static String base(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}
