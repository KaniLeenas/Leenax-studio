package com.leenax.studio.ai.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import com.leenax.studio.auth.CurrentUser;
import com.leenax.studio.auth.User;
import com.leenax.studio.projects.ProjectMembers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes job updates to open browser tabs with Server-Sent Events.
 * Single-instance by design (personal studio); browsers reconnect on their own.
 */
@Component
public class GenerationEvents {

    private static final Logger log = LoggerFactory.getLogger(GenerationEvents.class);
    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    /** One open event stream and who it belongs to: each person only hears about jobs they can see. */
    record Listener(SseEmitter emitter, UUID userId, boolean admin) {
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final JobMapper mapper;
    private final ProjectMembers members;
    private final CurrentUser current;

    public GenerationEvents(JobMapper mapper, ProjectMembers members, CurrentUser current) {
        this.mapper = mapper;
        this.members = members;
        this.current = current;
    }

    public SseEmitter subscribe(UUID userId, boolean admin) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Listener listener = new Listener(emitter, userId, admin);
        listeners.add(listener);
        emitter.onCompletion(() -> listeners.remove(listener));
        emitter.onTimeout(() -> listeners.remove(listener));
        emitter.onError(e -> listeners.remove(listener));
        try {
            emitter.send(SseEmitter.event().name("ready").data("ok"));
        } catch (IOException e) {
            listeners.remove(listener);
        }
        return emitter;
    }

    public void publish(GenerationJob job) {
        if (listeners.isEmpty()) return;
        Object payload;
        try {
            payload = mapper.toResponse(job);
        } catch (RuntimeException e) {
            log.debug("Could not map job {} for event: {}", job.getId(), e.getMessage());
            return;
        }
        // A job just created in this request isn't re-read yet: its owner is the person making the request.
        UUID owner = job.getCreatedBy() != null ? job.getCreatedBy() : current.find().map(User::getId).orElse(null);
        Map<UUID, Boolean> memberOf = new HashMap<>();
        SseEmitter.SseEventBuilder event = SseEmitter.event().name("job").data(payload);
        for (Listener l : listeners) {
            boolean visible = l.admin() || (job.getProjectId() == null
                    ? l.userId().equals(owner)
                    : memberOf.computeIfAbsent(l.userId(), u -> members.role(job.getProjectId(), u).isPresent()));
            if (visible) send(l, event);
        }
    }

    @Scheduled(fixedDelay = 20_000)
    public void heartbeat() {
        for (Listener l : listeners) send(l, SseEmitter.event().comment("keep-alive"));
    }

    private void send(Listener l, SseEmitter.SseEventBuilder event) {
        try {
            l.emitter().send(event);
        } catch (IOException | IllegalStateException e) {
            listeners.remove(l);
        }
    }
}
