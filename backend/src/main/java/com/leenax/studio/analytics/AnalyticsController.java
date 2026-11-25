package com.leenax.studio.analytics;

import com.leenax.studio.analytics.AnalyticsDtos.Overview;
import com.leenax.studio.analytics.AnalyticsDtos.Snapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService service;
    private final MetricsCollector collector;

    public AnalyticsController(AnalyticsService service, MetricsCollector collector) {
        this.service = service;
        this.collector = collector;
    }

    @GetMapping("/overview")
    public Overview overview(@RequestParam(defaultValue = "30") int days, @RequestParam(required = false) String tz) {
        return service.overview(days, tz);
    }

    @GetMapping("/targets/{id}/metrics")
    public List<Snapshot> history(@PathVariable UUID id) {
        return service.history(id);
    }

    /** Reads the latest numbers from the platforms now (at most every ten minutes). */
    @PostMapping("/refresh")
    public Map<String, Integer> refresh() {
        return Map.of("updated", collector.refreshNow());
    }
}
