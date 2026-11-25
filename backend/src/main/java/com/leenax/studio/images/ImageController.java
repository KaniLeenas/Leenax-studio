package com.leenax.studio.images;

import com.leenax.studio.images.ImageDtos.GenerateRequest;
import com.leenax.studio.images.ImageDtos.ImagesResult;
import com.leenax.studio.images.ImageDtos.RemoveBackgroundRequest;
import com.leenax.studio.images.ImageDtos.ResizeRequest;
import com.leenax.studio.images.ImageDtos.ToolsStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/images")
public class ImageController {

    private final ImageService service;

    public ImageController(ImageService service) {
        this.service = service;
    }

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public ImagesResult generate(@Valid @RequestBody GenerateRequest req) {
        return service.generate(req);
    }

    @GetMapping("/tools")
    public ToolsStatus tools() {
        return new ToolsStatus(service.backgroundRemovalAvailable(), ImageRecipes.PRESETS);
    }

    @PostMapping("/remove-background")
    @ResponseStatus(HttpStatus.CREATED)
    public ImagesResult removeBackground(@Valid @RequestBody RemoveBackgroundRequest req) {
        return service.removeBackground(req.mediaId());
    }

    @PostMapping("/resize")
    @ResponseStatus(HttpStatus.CREATED)
    public ImagesResult resize(@Valid @RequestBody ResizeRequest req) {
        return service.resize(req);
    }
}
