package com.leenax.studio.documents;

import com.leenax.studio.documents.DocumentDtos.DocumentDetail;
import com.leenax.studio.documents.DocumentDtos.DocumentList;
import com.leenax.studio.documents.DocumentDtos.DownloadUrl;
import com.leenax.studio.documents.DocumentDtos.PagesResponse;
import com.leenax.studio.documents.DocumentDtos.RenameRequest;
import com.leenax.studio.documents.DocumentDtos.ReprocessRequest;
import com.leenax.studio.documents.DocumentDtos.SearchResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/** Course documents (E2). Who may do what is checked here and again by the database. */
@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @GetMapping("/projects/{projectId}/documents")
    public DocumentList list(@PathVariable UUID projectId) {
        return service.list(projectId);
    }

    @PostMapping(value = "/projects/{projectId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentDetail upload(@PathVariable UUID projectId, @RequestPart("file") MultipartFile file,
                                 @RequestParam(required = false) String title) {
        return service.upload(projectId, file, title);
    }

    @GetMapping("/projects/{projectId}/documents/search")
    public SearchResponse search(@PathVariable UUID projectId, @RequestParam("q") String query) {
        return service.search(projectId, query == null ? "" : query.length() > 200 ? query.substring(0, 200) : query);
    }

    @GetMapping("/documents/{id}")
    public DocumentDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PatchMapping("/documents/{id}")
    public DocumentDetail rename(@PathVariable UUID id, @Valid @RequestBody RenameRequest req) {
        return service.rename(id, req.title());
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping(value = "/documents/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentDetail addVersion(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return service.addVersion(id, file);
    }

    @GetMapping("/documents/versions/{versionId}/pages")
    public PagesResponse pages(@PathVariable UUID versionId, @RequestParam(defaultValue = "1") int from) {
        return service.pages(versionId, from);
    }

    @GetMapping("/documents/versions/{versionId}/download-url")
    public DownloadUrl downloadUrl(@PathVariable UUID versionId) {
        return service.downloadUrl(versionId);
    }

    @PostMapping("/documents/versions/{versionId}/reprocess")
    public DocumentDetail reprocess(@PathVariable UUID versionId, @RequestBody(required = false) ReprocessRequest req) {
        return service.reprocess(versionId, req != null && req.forceOcr());
    }
}
