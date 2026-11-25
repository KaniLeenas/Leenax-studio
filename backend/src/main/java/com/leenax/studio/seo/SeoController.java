package com.leenax.studio.seo;

import com.leenax.studio.common.ApiException;
import com.leenax.studio.seo.SeoService.Budget;
import com.leenax.studio.seo.SeoService.Overview;
import com.leenax.studio.seo.SeoService.ResearchRequest;
import com.leenax.studio.seo.SeoService.TrendsInfo;
import com.leenax.studio.seo.SeoStore.ResearchRow;
import com.leenax.studio.syllabus.AnalysisStore.JobRow;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/** YouTube SEO research and reports (E6). Reports are reviewed through the approval routes. */
@RestController
@RequestMapping("/api/v1")
public class SeoController {

    private final SeoService seo;

    public SeoController(SeoService seo) {
        this.seo = seo;
    }

    @GetMapping("/projects/{projectId}/seo")
    public Overview overview(@PathVariable UUID projectId) {
        return seo.overview(projectId);
    }

    @GetMapping("/seo/budget")
    public Budget budget() {
        return seo.budget();
    }

    @PostMapping("/projects/{projectId}/seo/research")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobRow research(@PathVariable UUID projectId, @Valid @RequestBody ResearchRequest req) {
        return seo.start(projectId, req);
    }

    @GetMapping("/seo/research/{researchId}")
    public ResearchRow research(@PathVariable UUID researchId) {
        return seo.research(researchId);
    }

    @PostMapping("/projects/{projectId}/seo/trends")
    @ResponseStatus(HttpStatus.CREATED)
    public TrendsInfo importTrends(@PathVariable UUID projectId, @RequestParam("file") MultipartFile file) {
        try {
            return seo.importTrends(projectId, file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw ApiException.badRequest("The file couldn't be read");
        }
    }

    @DeleteMapping("/seo/trends/{trendsId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTrends(@PathVariable UUID trendsId) {
        seo.removeTrends(trendsId);
    }
}
