package com.leenax.studio.voice;

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
import com.leenax.studio.voice.VoiceDtos.VoiceOption;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/voice")
public class VoiceController {

    private final VoiceService service;

    public VoiceController(VoiceService service) {
        this.service = service;
    }

    /** Voices available to an ElevenLabs or Gemini account (stock voices plus your clones). */
    @GetMapping("/voices")
    public List<VoiceOption> voices(@RequestParam UUID accountId) {
        return service.voices(accountId);
    }

    @PostMapping("/narrations")
    @ResponseStatus(HttpStatus.CREATED)
    public SpeechResult narrate(@Valid @RequestBody NarrationRequest req) {
        return service.narrate(req);
    }

    @PostMapping("/dialogues")
    @ResponseStatus(HttpStatus.CREATED)
    public SpeechResult dialogue(@Valid @RequestBody DialogueRequest req) {
        return service.dialogue(req);
    }

    /** Up to 300 characters, returned as MP3 and not saved. */
    @PostMapping("/preview")
    public ResponseEntity<byte[]> preview(@Valid @RequestBody PreviewRequest req) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg"))
                .header("Cache-Control", "no-store").body(service.preview(req));
    }

    @GetMapping("/profiles")
    public List<ProfileResponse> profiles() {
        return service.listProfiles();
    }

    @PostMapping("/profiles")
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileResponse createProfile(@Valid @RequestBody ProfileRequest req) {
        return service.createProfile(req);
    }

    @PatchMapping("/profiles/{id}")
    public ProfileResponse updateProfile(@PathVariable UUID id, @Valid @RequestBody ProfileUpdate req) {
        return service.updateProfile(id, req);
    }

    @DeleteMapping("/profiles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProfile(@PathVariable UUID id) {
        service.deleteProfile(id);
    }

    /** The consent sentence the UI shows and the clone request must repeat. */
    @GetMapping("/clone/consent")
    public Map<String, String> consent() {
        return Map.of("statement", VoiceDtos.CONSENT_STATEMENT);
    }

    @PostMapping("/clone")
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileResponse cloneVoice(@Valid @RequestBody CloneRequest req) {
        return service.cloneVoice(req);
    }

    @GetMapping("/pronunciations")
    public List<RuleResponse> rules() {
        return service.listRules();
    }

    @PostMapping("/pronunciations")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleResponse createRule(@Valid @RequestBody RuleRequest req) {
        return service.createRule(req);
    }

    @PutMapping("/pronunciations/{id}")
    public RuleResponse updateRule(@PathVariable UUID id, @Valid @RequestBody RuleRequest req) {
        return service.updateRule(id, req);
    }

    @DeleteMapping("/pronunciations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable UUID id) {
        service.deleteRule(id);
    }
}
