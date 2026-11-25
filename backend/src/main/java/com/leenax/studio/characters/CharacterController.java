package com.leenax.studio.characters;

import com.leenax.studio.characters.CharacterDtos.CharacterRequest;
import com.leenax.studio.characters.CharacterDtos.CharacterResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/characters")
public class CharacterController {

    private final CharacterService service;

    public CharacterController(CharacterService service) {
        this.service = service;
    }

    /** With projectId: that project's characters plus the shared ones. Without: all. */
    @GetMapping
    public List<CharacterResponse> list(@RequestParam(required = false) UUID projectId) {
        return service.list(projectId);
    }

    @GetMapping("/{id}")
    public CharacterResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CharacterResponse create(@Valid @RequestBody CharacterRequest r) {
        return service.create(r);
    }

    @PutMapping("/{id}")
    public CharacterResponse update(@PathVariable UUID id, @Valid @RequestBody CharacterRequest r) {
        return service.update(id, r);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
