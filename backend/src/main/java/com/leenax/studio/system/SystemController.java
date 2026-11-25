package com.leenax.studio.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final SystemStatusService service;

    public SystemController(SystemStatusService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public SystemStatusService.Status status() {
        return service.status();
    }
}
