package com.leenax.studio.voice;

import com.leenax.studio.ai.accounts.AccountService;
import com.leenax.studio.ai.accounts.AiAccount;
import com.leenax.studio.ai.credits.UsageRecorder;
import com.leenax.studio.ai.provider.MediaTypes.Clip;
import com.leenax.studio.ai.provider.MediaTypes.Sample;
import com.leenax.studio.ai.provider.MediaTypes.SpeechRequest;
import com.leenax.studio.ai.provider.MediaTypes.VoiceInfo;
import com.leenax.studio.ai.provider.ProviderCode;
import com.leenax.studio.ai.provider.ProviderCredentials;
import com.leenax.studio.ai.provider.ProviderException;
import com.leenax.studio.ai.provider.ProviderRegistry;
import com.leenax.studio.ai.provider.SpeechProvider;
import com.leenax.studio.audio.AudioRecipes;
import com.leenax.studio.audio.Ffmpeg;
import com.leenax.studio.audit.AuditService;
import com.leenax.studio.common.ApiException;
import com.leenax.studio.common.TempFiles;
import com.leenax.studio.media.MediaAsset;
import com.leenax.studio.media.MediaKind;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.media.MediaSource;
import com.leenax.studio.voice.VoiceDtos.CloneRequest;
import com.leenax.studio.voice.VoiceDtos.DialogueRequest;
import com.leenax.studio.voice.VoiceDtos.NarrationRequest;
import com.leenax.studio.voice.VoiceDtos.PreviewRequest;
import com.leenax.studio.voice.VoiceDtos.ProfileRequest;
import com.leenax.studio.voice.VoiceDtos.ProfileResponse;
import com.leenax.studio.voice.VoiceDtos.ProfileUpdate;
import com.leenax.studio.voice.VoiceDtos.RuleRequest;
import com.leenax.studio.voice.VoiceDtos.RuleResponse;
import com.leenax.studio.voice.VoiceDtos.SpeechResult;
import com.leenax.studio.voice.VoiceDtos.VoiceChoice;
import com.leenax.studio.voice.VoiceDtos.VoiceOption;
import com.leenax.studio.voice.VoiceDtos.VoiceStyle;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Text-to-speech, dialogue, voice profiles, consented voice cloning and
 * pronunciation rules. Generated speech is always saved as MP3 audio media.
 */
@Service
public class VoiceService {

    static final long MAX_SAMPLE_BYTES = 10L * 1024 * 1024;

    private final AccountService accounts;
    private final ProviderRegistry registry;
    private final VoiceProfileRepository profiles;
    private final PronunciationRuleRepository rules;
    private final MediaService media;
    private final Ffmpeg ffmpeg;
    private final UsageRecorder usage;
    private final AuditService audit;
    private final com.leenax.studio.media.MediaProbe probe;

    public VoiceService(AccountService accounts, ProviderRegistry registry, VoiceProfileRepository profiles,
                        PronunciationRuleRepository rules, MediaService media, Ffmpeg ffmpeg, UsageRecorder usage,
                        AuditService audit, com.leenax.studio.media.MediaProbe probe) {
        this.probe = probe;
        this.accounts = accounts;
        this.registry = registry;
        this.profiles = profiles;
        this.rules = rules;
        this.media = media;
        this.ffmpeg = ffmpeg;
        this.usage = usage;
        this.audit = audit;
    }

    /** Everything needed to speak one piece of text. */
    record Voice(AiAccount account, ProviderCredentials credentials, SpeechProvider provider, String voiceId, String model,
                 String language, VoiceStyles.Emotion emotion, String direction, double speed, double pitch,
                 Double stability, Double similarity) {
    }

    // ---- stock voices --------------------------------------------------------

    public List<VoiceOption> voices(UUID accountId) {
        var usable = accounts.forUse(accountId);
        SpeechProvider p = speechProvider(usable.account());
        return p.voices(usable.credentials()).stream().map(VoiceService::option).toList();
    }

    private static VoiceOption option(VoiceInfo v) {
        return new VoiceOption(v.id(), v.name(), v.category(), v.gender(), v.language(), v.accent(), v.description(),
                v.previewUrl(), v.cloned());
    }

    // ---- speech --------------------------------------------------------------

    public SpeechResult narrate(NarrationRequest req) {
        Voice voice = resolve(req.voice(), req.language(), req.style());
        try (TempFiles tmp = new TempFiles()) {
            Rendered r = render(voice, req.text(), tmp);
            String title = title(req.title(), req.text(), "Narration");
            MediaAsset asset = media.ingest(r.file(), title + ".mp3", MediaSource.AI_GENERATED, req.projectId());
            usage.record(voice.account(), voice.model(), "VOICE", seconds(asset), r.cost(), "CREDITS", true, true);
            return new SpeechResult(media.toResponse(asset), r.chunks(), r.characters());
        }
    }

    /** Narration made of several parts (scenes), saved as one MP3, with where each part starts and how long it is. */
    public record SceneNarration(MediaAsset media, List<double[]> parts) {
    }

    /**
     * Speaks each text in turn with the same voice and joins them with `gapSeconds`
     * of silence. Used by the auto-editor to time scenes to the narration.
     */
    public SceneNarration narrateScenes(VoiceChoice voice, String language, VoiceStyle style, List<String> texts, double gapSeconds,
                                        String title, UUID projectId) {
        if (texts.isEmpty()) throw ApiException.badRequest("Nothing to narrate");
        Voice v = resolve(voice, language, style);
        try (TempFiles tmp = new TempFiles()) {
            List<Path> files = new ArrayList<>();
            List<double[]> parts = new ArrayList<>();
            double t = 0;
            Integer cost = null;
            for (String text : texts) {
                Rendered r = render(v, text, tmp);
                Long ms = probe.probe(r.file(), MediaKind.AUDIO).durationMs();
                if (ms == null) throw ApiException.badRequest("Could not measure the narration audio");
                double d = ms / 1000.0;
                parts.add(new double[]{Math.round(t * 1000) / 1000.0, Math.round(d * 1000) / 1000.0});
                t += d + gapSeconds;
                files.add(r.file());
                if (r.cost() != null) cost = (cost == null ? 0 : cost) + r.cost();
            }
            Path out = tmp.create(".mp3");
            ffmpeg.run(files.size() == 1 ? AudioRecipes.toMp3(files.get(0), out) : AudioRecipes.concat(files, out, gapSeconds));
            MediaAsset asset = media.ingest(out, title(title, texts.get(0), "Narration") + ".mp3", MediaSource.AI_GENERATED, projectId);
            usage.record(v.account(), v.model(), "VOICE", seconds(asset), cost, "CREDITS", true, true);
            return new SceneNarration(asset, parts);
        }
    }

    /** Short sample returned straight to the browser and not saved. */
    public byte[] preview(PreviewRequest req) {
        Voice voice = resolve(req.voice(), req.language(), req.style());
        try (TempFiles tmp = new TempFiles()) {
            Rendered r = render(voice, req.text(), tmp);
            usage.record(voice.account(), voice.model(), "VOICE", null, r.cost(), "CREDITS", true, true);
            return Files.readAllBytes(r.file());
        } catch (IOException e) {
            throw new ApiException(com.leenax.studio.common.ErrorCode.INTERNAL_ERROR, "Could not read the preview");
        }
    }

    public SpeechResult dialogue(DialogueRequest req) {
        List<ScriptText.Line> lines = ScriptText.parseDialogue(req.script());
        if (lines.isEmpty()) throw ApiException.badRequest("Write the dialogue as one line per turn, like “Kani: Hello!”");
        if (lines.size() > 200) throw ApiException.badRequest("Keep dialogues under 200 lines");

        Map<String, Voice> voices = new HashMap<>();
        for (var s : req.speakers()) {
            voices.put(s.name().strip().toLowerCase(Locale.ROOT), resolve(new VoiceChoice(s.profileId(), null, null, null), req.language(), null));
        }
        for (var line : lines) {
            if (!voices.containsKey(line.speaker().toLowerCase(Locale.ROOT))) {
                throw ApiException.badRequest("Pick a voice for “" + line.speaker() + "”");
            }
        }
        try (TempFiles tmp = new TempFiles()) {
            List<Path> parts = new ArrayList<>();
            int chunks = 0;
            int characters = 0;
            Map<Voice, Integer> cost = new LinkedHashMap<>();
            for (var line : lines) {
                Voice v = voices.get(line.speaker().toLowerCase(Locale.ROOT));
                Rendered r = render(v, line.text(), tmp);
                parts.add(r.file());
                chunks += r.chunks();
                characters += r.characters();
                if (r.cost() != null) cost.merge(v, r.cost(), Integer::sum);
            }
            Path out = tmp.create(".mp3");
            double gap = req.gapSeconds() == null ? 0.3 : req.gapSeconds();
            ffmpeg.run(parts.size() == 1 ? AudioRecipes.toMp3(parts.get(0), out) : AudioRecipes.concat(parts, out, gap));
            MediaAsset asset = media.ingest(out, title(req.title(), lines.get(0).text(), "Dialogue") + ".mp3",
                    MediaSource.AI_GENERATED, req.projectId());
            for (Voice v : voices.values()) {
                usage.record(v.account(), v.model(), "VOICE", null, cost.get(v), "CREDITS", true, true);
            }
            return new SpeechResult(media.toResponse(asset), chunks, characters);
        }
    }

    record Rendered(Path file, int chunks, int characters, Integer cost) {
    }

    /**
     * Pronunciation → chunking → provider pauses → synthesis per chunk (with
     * neighbouring text for smooth joins) → join → speed/pitch → MP3.
     */
    Rendered render(Voice v, String rawText, TempFiles tmp) {
        String text = ScriptText.applyPronunciation(rawText.strip(), activeRules(v.language()));
        if (ScriptText.stripPauses(text).isBlank()) throw ApiException.badRequest("Write something to say");
        int limit = (int) (v.provider().maxChars(v.model()) * 0.85);
        List<String> chunks = ScriptText.chunk(text, limit);

        boolean eleven = v.provider().code() == ProviderCode.ELEVENLABS;
        double[] speed = VoiceStyles.splitSpeed(v.speed(), eleven);
        VoiceStyles.ElevenSettings preset = VoiceStyles.eleven(v.emotion());
        String direction = eleven ? null : VoiceStyles.direction(v.emotion(), v.direction());

        List<Path> parts = new ArrayList<>();
        Integer cost = null;
        int characters = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String spoken = ScriptText.renderPauses(chunks.get(i), v.provider()::pauseMarkup);
            characters += ScriptText.stripPauses(chunks.get(i)).length();
            String prev = i > 0 ? tail(ScriptText.stripPauses(chunks.get(i - 1))) : null;
            String next = i + 1 < chunks.size() ? head(ScriptText.stripPauses(chunks.get(i + 1))) : null;
            SpeechRequest request = new SpeechRequest(v.model(), v.voiceId(), spoken, languageCode(v.language()), direction,
                    eleven ? (v.stability() != null ? v.stability() : preset.stability()) : null,
                    eleven ? (v.similarity() != null ? v.similarity() : preset.similarity()) : null,
                    eleven ? preset.exaggeration() : null,
                    eleven ? speed[0] : null,
                    eleven ? prev : null, eleven ? next : null);
            Clip clip;
            try {
                clip = v.provider().synthesize(request, v.credentials());
            } catch (ProviderException e) {
                usage.record(v.account(), v.model(), "VOICE", null, null, null, false, false);
                throw e;
            }
            if (clip.cost() != null) cost = (cost == null ? 0 : cost) + clip.cost();
            parts.add(tmp.write(clip.bytes(), TempFiles.extensionFor(clip.mimeType())));
        }

        Path joined = tmp.create(".mp3");
        ffmpeg.run(parts.size() == 1 ? AudioRecipes.toMp3(parts.get(0), joined) : AudioRecipes.concat(parts, joined, 0.05));
        if (Math.abs(speed[1] - 1) > 0.01 || Math.abs(v.pitch()) > 0.01) {
            Path adjusted = tmp.create(".mp3");
            ffmpeg.run(AudioRecipes.speedAndPitch(joined, adjusted, speed[1], v.pitch()));
            joined = adjusted;
        }
        return new Rendered(joined, chunks.size(), characters, cost);
    }

    private static String tail(String s) {
        return s.length() <= 300 ? s : s.substring(s.length() - 300);
    }

    private static String head(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300);
    }

    /** ISO 639-1 codes the providers expect. */
    static String languageCode(String language) {
        if (language == null) return null;
        return switch (language.toUpperCase(Locale.ROOT)) {
            case "TA" -> "ta";
            case "EN" -> "en";
            default -> null;
        };
    }

    private List<ScriptText.Rule> activeRules(String language) {
        return rules.findAllByOrderByTermAsc().stream()
                .filter(r -> r.getLanguage() == null || language == null || r.getLanguage().equalsIgnoreCase(language))
                .sorted((a, b) -> b.getTerm().length() - a.getTerm().length()) // longer terms first
                .map(r -> new ScriptText.Rule(r.getTerm(), r.getReplacement()))
                .toList();
    }

    @Transactional(readOnly = true)
    Voice resolve(VoiceChoice choice, String language, VoiceStyle style) {
        String voiceId;
        String model;
        String lang = language;
        UUID accountId;
        Map<String, Object> saved = Map.of();
        if (choice.profileId() != null) {
            VoiceProfile p = profiles.findById(choice.profileId()).orElseThrow(() -> ApiException.notFound("Voice profile"));
            accountId = p.getAccountId();
            voiceId = p.getProviderVoiceId();
            model = choice.model() != null && !choice.model().isBlank() ? choice.model() : p.getModelCode();
            if (lang == null) lang = p.getLanguage();
            saved = p.getSettings();
        } else {
            if (choice.accountId() == null || choice.voiceId() == null || choice.voiceId().isBlank()) {
                throw ApiException.badRequest("Choose a voice");
            }
            accountId = choice.accountId();
            voiceId = choice.voiceId().strip();
            model = choice.model();
        }
        var usable = accounts.forUse(accountId);
        SpeechProvider provider = speechProvider(usable.account());
        if (model == null || model.isBlank()) model = null;

        String emotion = style != null && style.emotion() != null ? style.emotion() : str(saved.get("emotion"));
        String direction = style != null && style.direction() != null ? style.direction() : str(saved.get("direction"));
        double speed = style != null && style.speed() != null ? style.speed() : num(saved.get("speed"), 1.0);
        double pitch = style != null && style.pitch() != null ? style.pitch() : num(saved.get("pitch"), 0.0);
        Double stability = style != null && style.stability() != null ? style.stability() : numOrNull(saved.get("stability"));
        Double similarity = style != null && style.similarity() != null ? style.similarity() : numOrNull(saved.get("similarity"));
        return new Voice(usable.account(), usable.credentials(), provider, voiceId, model, lang,
                VoiceStyles.parse(emotion), direction, speed, pitch, stability, similarity);
    }

    private SpeechProvider speechProvider(AiAccount account) {
        return registry.speech(account.getProvider()).orElseThrow(() ->
                ApiException.badRequest(account.getProvider().displayName() + " cannot make voice-overs. Use an ElevenLabs, Google Gemini or Google Cloud Text-to-Speech account."));
    }

    // ---- profiles ------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ProfileResponse> listProfiles() {
        Map<UUID, String> names = new HashMap<>();
        accounts.list().forEach(a -> names.put(a.getId(), a.getName()));
        return profiles.findAllByOrderByNameAsc().stream().map(p -> toResponse(p, names.get(p.getAccountId()))).toList();
    }

    @Transactional
    public ProfileResponse createProfile(ProfileRequest req) {
        AiAccount account = accounts.get(req.accountId());
        speechProvider(account);
        VoiceProfile p = new VoiceProfile(account.getId(), account.getProvider(), req.voiceId().strip(), req.name().strip(),
                VoiceProfile.Kind.STOCK);
        p.setLanguage(req.language());
        p.setModelCode(blankToNull(req.model()));
        p.setSettings(settingsFrom(req.style(), Map.of()));
        p = profiles.save(p);
        audit.record("VOICE_PROFILE_CREATED", "voice_profile", p.getId(), Map.of("name", p.getName()));
        return toResponse(p, account.getName());
    }

    @Transactional
    public ProfileResponse updateProfile(UUID id, ProfileUpdate req) {
        VoiceProfile p = profiles.findById(id).orElseThrow(() -> ApiException.notFound("Voice profile"));
        if (req.name() != null) {
            if (req.name().isBlank()) throw ApiException.badRequest("Name cannot be blank");
            p.setName(req.name().strip());
        }
        if (req.language() != null) p.setLanguage(req.language());
        if (req.model() != null) p.setModelCode(blankToNull(req.model()));
        if (req.style() != null) p.setSettings(settingsFrom(req.style(), p.getSettings()));
        if (req.shared() != null && req.shared() != p.isShared()) {
            p.setShared(req.shared());
            audit.record(req.shared() ? "VOICE_SHARED" : "VOICE_UNSHARED", "voice_profile", p.getId(), Map.of("name", p.getName()));
        }
        profiles.flush();   // a voice that isn't yours touches no row: answered as "can't edit it here"
        return toResponse(p, accounts.get(p.getAccountId()).getName());
    }

    /** Deleting a cloned voice also deletes it from the provider, so the clone doesn't linger there. */
    public void deleteProfile(UUID id) {
        VoiceProfile p = profiles.findById(id).orElseThrow(() -> ApiException.notFound("Voice profile"));
        if (p.getKind() == VoiceProfile.Kind.CLONED) {
            var usable = accounts.forUse(p.getAccountId());
            try {
                speechProvider(usable.account()).deleteVoice(p.getProviderVoiceId(), usable.credentials());
            } catch (ProviderException e) {
                if (e.httpStatus() != 404) throw e; // already gone at the provider
            }
        }
        profiles.delete(p);
        audit.record("VOICE_PROFILE_DELETED", "voice_profile", id, Map.of("name", p.getName(), "kind", p.getKind().name()));
    }

    // ---- cloning -------------------------------------------------------------

    /**
     * Creates a private clone of the owner's own voice. Requires the consent
     * checkbox and the consent sentence typed exactly; both are stored with the profile.
     */
    public ProfileResponse cloneVoice(CloneRequest req) {
        if (!req.consentOwnVoice() || !Consent.matches(req.consentStatement())) {
            throw ApiException.badRequest("Confirm it is your own voice and type the consent sentence exactly as shown");
        }
        var usable = accounts.forUse(req.accountId());
        SpeechProvider provider = speechProvider(usable.account());
        if (!provider.supportsCloning()) {
            throw ApiException.badRequest(usable.account().getProvider().displayName() + " doesn't offer voice cloning. Use an ElevenLabs account.");
        }
        List<Sample> samples = new ArrayList<>();
        List<String> checksums = new ArrayList<>();
        long total = 0;
        for (UUID id : req.sampleMediaIds()) {
            Sample s = media.sample(id, Set.of(MediaKind.AUDIO), MAX_SAMPLE_BYTES, "Voice samples must be audio recordings");
            total += s.bytes().length;
            samples.add(s);
            checksums.add(media.findAsset(id).getChecksumSha256());
        }
        if (total < 50_000) throw ApiException.badRequest("Add at least one clear recording of about a minute or more");

        VoiceInfo created = provider.cloneVoice(req.name().strip(), req.description(), samples, req.removeBackgroundNoise(), usable.credentials());

        Map<String, Object> consent = new LinkedHashMap<>();
        consent.put("ownVoice", true);
        consent.put("statement", VoiceDtos.CONSENT_STATEMENT);
        consent.put("confirmedAt", Instant.now().toString());
        consent.put("sampleChecksums", checksums);
        consent.put("removeBackgroundNoise", req.removeBackgroundNoise());

        VoiceProfile p = new VoiceProfile(usable.account().getId(), usable.account().getProvider(), created.id(),
                req.name().strip(), VoiceProfile.Kind.CLONED);
        p.setLanguage(req.language());
        p.setConsent(consent);
        p.setSampleMediaIds(req.sampleMediaIds().stream().map(UUID::toString).toList());
        try {
            p = profiles.save(p);
        } catch (RuntimeException e) {
            // keep provider and app in step: don't leave an orphan clone at the provider
            try {
                provider.deleteVoice(created.id(), usable.credentials());
            } catch (RuntimeException ignored) {
                // best effort
            }
            throw e;
        }
        audit.record("VOICE_CLONED", "voice_profile", p.getId(),
                Map.of("name", p.getName(), "provider", p.getProvider().name(), "samples", samples.size()));
        return toResponse(p, usable.account().getName());
    }

    // ---- pronunciation rules -------------------------------------------------

    @Transactional(readOnly = true)
    public List<RuleResponse> listRules() {
        return rules.findAllByOrderByTermAsc().stream().map(VoiceService::toResponse).toList();
    }

    @Transactional
    public RuleResponse createRule(RuleRequest req) {
        try {
            PronunciationRule r = rules.saveAndFlush(new PronunciationRule(req.term().strip(), req.replacement().strip(), req.language()));
            return toResponse(r);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(com.leenax.studio.common.ErrorCode.CONFLICT, "There is already a rule for “" + req.term().strip() + "”");
        }
    }

    @Transactional
    public RuleResponse updateRule(UUID id, RuleRequest req) {
        PronunciationRule r = rules.findById(id).orElseThrow(() -> ApiException.notFound("Pronunciation rule"));
        r.setTerm(req.term().strip());
        r.setReplacement(req.replacement().strip());
        r.setLanguage(req.language());
        try {
            rules.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(com.leenax.studio.common.ErrorCode.CONFLICT, "There is already a rule for “" + req.term().strip() + "”");
        }
        return toResponse(r);
    }

    @Transactional
    public void deleteRule(UUID id) {
        rules.deleteById(id);
    }

    // ---- helpers -------------------------------------------------------------

    static Map<String, Object> settingsFrom(VoiceStyle style, Map<String, Object> current) {
        Map<String, Object> s = new HashMap<>(current);
        if (style == null) return s;
        if (style.emotion() != null) s.put("emotion", style.emotion().toUpperCase(Locale.ROOT));
        if (style.direction() != null) s.put("direction", style.direction().strip());
        if (style.speed() != null) s.put("speed", style.speed());
        if (style.pitch() != null) s.put("pitch", style.pitch());
        if (style.stability() != null) s.put("stability", style.stability());
        if (style.similarity() != null) s.put("similarity", style.similarity());
        return s;
    }

    private ProfileResponse toResponse(VoiceProfile p, String accountName) {
        return new ProfileResponse(p.getId(), p.getAccountId(), accountName, p.getProvider(), p.getProviderVoiceId(),
                p.getName(), p.getKind(), p.getLanguage(), p.getModelCode(), p.getSettings(), p.getConsent(),
                p.getSampleMediaIds(), p.isShared(), p.getCreatedBy(), p.getCreatedAt());
    }

    private static RuleResponse toResponse(PronunciationRule r) {
        return new RuleResponse(r.getId(), r.getTerm(), r.getReplacement(), r.getLanguage());
    }

    static String title(String requested, String text, String fallback) {
        if (requested != null && !requested.isBlank()) return requested.strip();
        String t = ScriptText.stripPauses(text).replaceAll("\\s+", " ").strip();
        if (t.isEmpty()) return fallback;
        return t.length() > 40 ? t.substring(0, 40).strip() + "…" : t;
    }

    private static Double seconds(MediaAsset a) {
        return a.getDurationMs() == null ? null : a.getDurationMs() / 1000.0;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static double num(Object o, double fallback) {
        Double d = numOrNull(o);
        return d == null ? fallback : d;
    }

    private static Double numOrNull(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
