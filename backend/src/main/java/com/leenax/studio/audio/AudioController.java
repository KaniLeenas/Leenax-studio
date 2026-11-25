package com.leenax.studio.audio;

import com.leenax.studio.audio.AudioDtos.AudioResult;
import com.leenax.studio.audio.AudioDtos.CleanupRequest;
import com.leenax.studio.audio.AudioDtos.ExtractRequest;
import com.leenax.studio.audio.AudioDtos.MixRequest;
import com.leenax.studio.audio.AudioDtos.SoundEffectRequest;
import com.leenax.studio.audio.AudioDtos.SpeedPitchRequest;
import com.leenax.studio.media.MediaDtos.MediaResponse;
import com.leenax.studio.media.MediaService;
import com.leenax.studio.media.MediaSource;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audio")
public class AudioController {

    private final AudioService service;
    private final MediaService media;

    public AudioController(AudioService service, MediaService media) {
        this.service = service;
        this.media = media;
    }

    /** Saves a microphone recording from the browser (WebM/Opus, MP4/AAC or WAV) as audio media. */
    @PostMapping(path = "/recordings", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MediaResponse record(@RequestPart("file") MultipartFile file, @RequestParam(required = false) UUID projectId) {
        return media.upload(file, projectId, MediaSource.RECORDING);
    }

    @PostMapping("/cleanup")
    @ResponseStatus(HttpStatus.CREATED)
    public AudioResult cleanup(@Valid @RequestBody CleanupRequest req) {
        return service.cleanup(req);
    }

    @PostMapping("/speed-pitch")
    @ResponseStatus(HttpStatus.CREATED)
    public AudioResult speedPitch(@Valid @RequestBody SpeedPitchRequest req) {
        return service.speedPitch(req);
    }

    @PostMapping("/mix")
    @ResponseStatus(HttpStatus.CREATED)
    public AudioResult mix(@Valid @RequestBody MixRequest req) {
        return service.mix(req);
    }

    @PostMapping("/extract")
    @ResponseStatus(HttpStatus.CREATED)
    public AudioResult extract(@Valid @RequestBody ExtractRequest req) {
        return service.extract(req.videoMediaId());
    }

    @PostMapping("/sound-effects")
    @ResponseStatus(HttpStatus.CREATED)
    public AudioResult soundEffect(@Valid @RequestBody SoundEffectRequest req) {
        return service.soundEffect(req);
    }
}
