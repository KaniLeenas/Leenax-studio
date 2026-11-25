package com.leenax.studio.media;

import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.ErrorCode;
import com.leenax.studio.media.FileTypeSniffer.DetectedType;
import com.leenax.studio.media.MediaDtos.DownloadUrlResponse;
import com.leenax.studio.media.MediaDtos.MediaResponse;
import com.leenax.studio.media.MediaDtos.UpdateMediaRequest;
import com.leenax.studio.media.storage.ObjectStorage;
import com.leenax.studio.media.storage.StorageProperties;
import com.leenax.studio.projects.ProjectRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class MediaService {

    private final MediaAssetRepository media;
    private final ProjectRepository projects;
    private final ObjectStorage storage;
    private final StorageProperties storageProps;
    private final MediaProbe probe;
    private final AuditService audit;

    public MediaService(MediaAssetRepository media, ProjectRepository projects, ObjectStorage storage,
                        StorageProperties storageProps, MediaProbe probe, AuditService audit) {
        this.media = media;
        this.projects = projects;
        this.storage = storage;
        this.storageProps = storageProps;
        this.probe = probe;
        this.audit = audit;
    }

    // ---- upload ---------------------------------------------------------

    /**
     * Validates by content (magic bytes), extracts metadata and a poster frame,
     * stores the file privately and records it. Not @Transactional: storage
     * uploads can take minutes and must not hold a DB connection.
     */
    public MediaResponse upload(MultipartFile file, UUID projectId) {
        return upload(file, projectId, MediaSource.UPLOAD);
    }

    /** Uploads and microphone recordings (source RECORDING) share the same checks. */
    public MediaResponse upload(MultipartFile file, UUID projectId, MediaSource source) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("File is empty");
        }
        if (projectId != null && !projects.existsById(projectId)) {
            throw ApiException.notFound("Project");
        }
        Path temp = null;
        try {
            temp = Files.createTempFile("leenax-upload-", ".bin");
            file.transferTo(temp);
            MediaAsset asset = ingest(temp, file.getOriginalFilename(), source, projectId);
            return toResponse(asset);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Could not process the upload");
        } finally {
            MediaProbe.deleteQuietly(temp);
        }
    }

    /**
     * Stores a file that is already on local disk (an upload or a generated video).
     * The caller owns {@code file} and deletes it afterwards.
     */
    public MediaAsset ingest(Path file, String originalFilename, MediaSource source, UUID projectId) {
        if (projectId != null && !projects.existsById(projectId)) {
            projectId = null; // project deleted meanwhile: keep the media, unassigned
        }
        Path poster = null;
        String key = null;
        String posterKey = null;
        try {
            DetectedType type = FileTypeSniffer.detect(readHeader(file))
                    .orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                            "Unsupported file type. Upload MP4, MOV, WebM, MKV, JPG, PNG, WebP, GIF, MP3, WAV, M4A, AAC, FLAC or OGG."));

            String checksum = sha256(file);
            MediaProbe.Metadata meta = probe.probe(file, type.kind());
            type = audioOnlyContainer(type, meta);

            UUID objectId = UUID.randomUUID();
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            key = "media/%d/%02d/%s.%s".formatted(today.getYear(), today.getMonthValue(), objectId, type.extension());
            storage.put(key, file, type.contentType());

            if (type.kind() == MediaKind.VIDEO) {
                poster = probe.posterFrame(file, meta.durationMs()).orElse(null);
                if (poster != null) {
                    posterKey = "posters/%s.jpg".formatted(objectId);
                    storage.put(posterKey, poster, "image/jpeg");
                }
            }

            MediaAsset asset = new MediaAsset(type.kind(), source, cleanFilename(originalFilename, type),
                    type.contentType(), Files.size(file), checksum, key);
            asset.setProjectId(projectId);
            asset.setPosterStorageKey(posterKey);
            asset.setWidth(meta.width());
            asset.setHeight(meta.height());
            asset.setDurationMs(meta.durationMs());
            asset = media.save(asset);

            audit.record(source == MediaSource.UPLOAD ? "MEDIA_UPLOADED" : "MEDIA_GENERATED", "media_asset", asset.getId(),
                    Map.of("kind", type.kind().name(), "sizeBytes", asset.getSizeBytes()));
            return asset;
        } catch (IOException e) {
            cleanupStored(key, posterKey);
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Could not process the file");
        } catch (RuntimeException e) {
            cleanupStored(key, posterKey);
            throw e;
        } finally {
            MediaProbe.deleteQuietly(poster);
        }
    }

    /** An image prepared for image-to-video: its bytes plus a short-lived signed link. */
    public record SourceImage(UUID id, byte[] bytes, String contentType, String signedUrl) {
    }

    public static final long MAX_SOURCE_IMAGE_BYTES = 20L * 1024 * 1024;

    @Transactional(readOnly = true)
    public SourceImage sourceImage(UUID id) {
        MediaAsset asset = find(id);
        if (asset.getKind() != MediaKind.IMAGE) {
            throw ApiException.badRequest("Pick an image (JPG, PNG or WebP) to animate");
        }
        if (asset.getSizeBytes() > MAX_SOURCE_IMAGE_BYTES) {
            throw ApiException.badRequest("That image is larger than 20 MB. Use a smaller image.");
        }
        byte[] bytes = storage.read(asset.getStorageKey(), MAX_SOURCE_IMAGE_BYTES);
        return new SourceImage(asset.getId(), bytes, asset.getContentType(), storage.signedUrl(asset.getStorageKey(), null));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<MediaResponse> findResponse(UUID id) {
        return id == null ? java.util.Optional.empty() : media.findById(id).map(this::toResponse);
    }

    // ---- queries --------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<MediaResponse> list(MediaKind kind, UUID projectId, boolean unassignedOnly, String search,
                                    int page, int size) {
        Specification<MediaAsset> spec = (root, query, cb) -> cb.conjunction();
        if (kind != null) spec = spec.and(MediaAssetRepository.hasKind(kind));
        if (projectId != null) spec = spec.and(MediaAssetRepository.inProject(projectId));
        else if (unassignedOnly) spec = spec.and(MediaAssetRepository.unassigned());
        if (search != null && !search.isBlank()) spec = spec.and(MediaAssetRepository.filenameContains(search.trim()));

        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return media.findAll(spec, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public java.util.List<MediaResponse> recent() {
        return media.findTop8ByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public MediaResponse get(UUID id) {
        return toResponse(find(id));
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse downloadUrl(UUID id) {
        MediaAsset asset = find(id);
        String url = storage.signedUrl(asset.getStorageKey(), asset.getOriginalFilename());
        return new DownloadUrlResponse(url, Instant.now().plusSeconds(storageProps.signedUrlMinutes() * 60L));
    }

    // ---- changes --------------------------------------------------------

    @Transactional
    public MediaResponse update(UUID id, UpdateMediaRequest req) {
        MediaAsset asset = find(id);
        if (req.filename() != null) {
            if (req.filename().isBlank()) throw ApiException.badRequest("Filename cannot be blank");
            asset.setOriginalFilename(req.filename().trim());
        }
        if (Boolean.TRUE.equals(req.unassign())) {
            asset.setProjectId(null);
        } else if (req.projectId() != null) {
            if (!projects.existsById(req.projectId())) throw ApiException.notFound("Project");
            asset.setProjectId(req.projectId());
        }
        return toResponse(asset);
    }

    @Transactional
    public void delete(UUID id) {
        MediaAsset asset = find(id);
        media.delete(asset);
        media.flush();
        cleanupStored(asset.getStorageKey(), asset.getPosterStorageKey());
        audit.record("MEDIA_DELETED", "media_asset", id, Map.of("filename", asset.getOriginalFilename()));
    }

    // ---- local copies (FFmpeg, providers, canvas) -------------------------

    /** A stored media file copied to local disk. The caller deletes {@code path}. */
    public record LocalFile(MediaAsset asset, Path path) {
    }

    public static final long MAX_LOCAL_COPY_BYTES = 2L * 1024 * 1024 * 1024;

    /** Copies a media file to a temp file, checking it is one of the allowed kinds. */
    @Transactional(readOnly = true)
    public LocalFile localCopy(UUID id, java.util.Set<MediaKind> allowed, String wrongKindMessage) {
        MediaAsset asset = find(id);
        if (!allowed.contains(asset.getKind())) throw ApiException.badRequest(wrongKindMessage);
        if (asset.getSizeBytes() > MAX_LOCAL_COPY_BYTES) throw ApiException.badRequest("That file is larger than 2 GB");
        try {
            String ext = asset.getOriginalFilename().contains(".")
                    ? asset.getOriginalFilename().substring(asset.getOriginalFilename().lastIndexOf('.')) : "";
            Path tmp = Files.createTempFile("leenax-media-", ext.replaceAll("[^.A-Za-z0-9]", ""));
            storage.download(asset.getStorageKey(), tmp);
            return new LocalFile(asset, tmp);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Could not prepare the file");
        }
    }

    /** Small files (images) read into memory, with their media type. */
    @Transactional(readOnly = true)
    public com.leenax.studio.ai.provider.MediaTypes.Sample sample(UUID id, java.util.Set<MediaKind> allowed, long maxBytes,
                                                                 String wrongKindMessage) {
        MediaAsset asset = find(id);
        if (!allowed.contains(asset.getKind())) throw ApiException.badRequest(wrongKindMessage);
        if (asset.getSizeBytes() > maxBytes) {
            throw ApiException.badRequest("“" + asset.getOriginalFilename() + "” is larger than " + (maxBytes / 1024 / 1024) + " MB");
        }
        return new com.leenax.studio.ai.provider.MediaTypes.Sample(storage.read(asset.getStorageKey(), maxBytes),
                asset.getOriginalFilename(), asset.getContentType());
    }

    /** Streams a file through the app (same origin) so the thumbnail canvas can export it. */
    @Transactional(readOnly = true)
    public MediaAsset findAsset(UUID id) {
        return find(id);
    }

    public InputStream openContent(MediaAsset asset) {
        return storage.open(asset.getStorageKey());
    }

    /** Media by id (missing ids are simply absent). */
    @Transactional(readOnly = true)
    public java.util.List<MediaAsset> findAssets(java.util.Collection<UUID> ids) {
        return ids.isEmpty() ? java.util.List.of() : media.findAllById(ids);
    }

    /** Link the renderer uses to read a file inside the Docker network. */
    public String internalUrl(MediaAsset asset, java.time.Duration validFor) {
        return storage.internalSignedUrl(asset.getStorageKey(), validFor);
    }

    // ---- helpers --------------------------------------------------------

    /** Microphone recordings arrive as WebM/MP4 with sound only: store those as audio, not video. */
    static DetectedType audioOnlyContainer(DetectedType type, MediaProbe.Metadata meta) {
        if (type.kind() != MediaKind.VIDEO || !Boolean.FALSE.equals(meta.hasVideo()) || !Boolean.TRUE.equals(meta.hasAudio())) {
            return type;
        }
        return switch (type.extension()) {
            case "webm" -> new DetectedType(MediaKind.AUDIO, "audio/webm", "webm");
            case "mkv" -> new DetectedType(MediaKind.AUDIO, "audio/x-matroska", "mka");
            default -> new DetectedType(MediaKind.AUDIO, "audio/mp4", "m4a");
        };
    }

    public MediaResponse toResponse(MediaAsset a) {
        String url = storage.signedUrl(a.getStorageKey(), null);
        String posterUrl = a.getPosterStorageKey() != null
                ? storage.signedUrl(a.getPosterStorageKey(), null)
                : (a.getKind() == MediaKind.IMAGE ? url : null);
        return new MediaResponse(a.getId(), a.getProjectId(), a.getKind(), a.getSource(), a.getOriginalFilename(),
                a.getContentType(), a.getSizeBytes(), a.getWidth(), a.getHeight(), a.getDurationMs(),
                a.getCreatedAt(), url, posterUrl);
    }

    private MediaAsset find(UUID id) {
        return media.findById(id).orElseThrow(() -> ApiException.notFound("Media"));
    }

    private void cleanupStored(String key, String posterKey) {
        if (key != null) storage.delete(key);
        if (posterKey != null) storage.delete(posterKey);
    }

    private static byte[] readHeader(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(FileTypeSniffer.HEADER_BYTES);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Keeps the user's name for display, but forces the extension to match the real content. */
    static String cleanFilename(String original, DetectedType type) {
        String name = Optional.ofNullable(original).orElse("upload");
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);              // strip any path
        name = name.replaceAll("[\\p{Cntrl}\"<>|:*?]", "_").trim();     // strip control/unsafe characters
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        if (base.isBlank()) base = "upload";
        if (base.length() > 200) base = base.substring(0, 200);
        return base + "." + type.extension();
    }
}
