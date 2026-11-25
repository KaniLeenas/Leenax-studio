package com.leenax.studio.notes;

import com.leenax.studio.approvals.Review.Kind;
import com.leenax.studio.approvals.ReviewDtos.ResourceState;
import com.leenax.studio.notes.ExportService.ExportFile;
import com.leenax.studio.notes.ExportService.FigureRequest;
import com.leenax.studio.notes.Learning.Question;
import com.leenax.studio.notes.MaterialsService.ByHandRequest;
import com.leenax.studio.notes.MaterialsService.JobInfo;
import com.leenax.studio.notes.MaterialsService.NotesRequest;
import com.leenax.studio.notes.MaterialsService.Overview;
import com.leenax.studio.notes.MaterialsService.QuestionsRequest;
import com.leenax.studio.notes.MaterialsService.RegenerateRequest;
import com.leenax.studio.notes.QuizService.Quiz;
import com.leenax.studio.notes.QuizService.QuizRequest;
import com.leenax.studio.notes.QuizService.QuizSummary;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Notes, question banks, quizzes and their exports (E4). Versions and decisions are under /reviews. */
@RestController
@RequestMapping("/api/v1")
public class MaterialsController {

    private final MaterialsService materials;
    private final QuizService quizzes;
    private final ExportService exports;

    public MaterialsController(MaterialsService materials, QuizService quizzes, ExportService exports) {
        this.materials = materials;
        this.quizzes = quizzes;
        this.exports = exports;
    }

    @GetMapping("/projects/{projectId}/materials")
    public Overview overview(@PathVariable UUID projectId) {
        return materials.overview(projectId);
    }

    @PostMapping("/projects/{projectId}/notes/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobInfo generateNotes(@PathVariable UUID projectId, @Valid @RequestBody NotesRequest req) {
        return materials.generateNotes(projectId, req);
    }

    @PostMapping("/projects/{projectId}/notes/manual")
    @ResponseStatus(HttpStatus.CREATED)
    public ResourceState notesByHand(@PathVariable UUID projectId, @Valid @RequestBody ByHandRequest req) {
        return materials.byHand(projectId, Kind.NOTES, req);
    }

    @PostMapping("/projects/{projectId}/mcq/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobInfo generateQuestions(@PathVariable UUID projectId, @Valid @RequestBody QuestionsRequest req) {
        return materials.generateQuestions(projectId, req);
    }

    @PostMapping("/projects/{projectId}/mcq/manual")
    @ResponseStatus(HttpStatus.CREATED)
    public ResourceState bankByHand(@PathVariable UUID projectId, @Valid @RequestBody ByHandRequest req) {
        return materials.byHand(projectId, Kind.MCQ, req);
    }

    /** Ties notes or a bank to the newly approved topic list or notes. */
    @PostMapping("/materials/{resourceId}/update")
    public ResourceState update(@PathVariable UUID resourceId) {
        return materials.update(resourceId);
    }

    /** A replacement for one question, not saved. */
    @PostMapping("/mcq/{resourceId}/regenerate")
    public Question regenerate(@PathVariable UUID resourceId, @Valid @RequestBody RegenerateRequest req) {
        return materials.regenerate(resourceId, req);
    }

    @PostMapping("/projects/{projectId}/figures")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveFigure(@PathVariable UUID projectId, @Valid @RequestBody FigureRequest req) {
        exports.saveFigure(projectId, req);
    }

    // ---- quizzes -----------------------------------------------------------------------------------

    @GetMapping("/projects/{projectId}/quizzes")
    public List<QuizSummary> quizzes(@PathVariable UUID projectId) {
        return quizzes.list(projectId);
    }

    @PostMapping("/projects/{projectId}/quizzes")
    @ResponseStatus(HttpStatus.CREATED)
    public Quiz createQuiz(@PathVariable UUID projectId, @Valid @RequestBody QuizRequest req) {
        return quizzes.create(projectId, req);
    }

    @GetMapping("/quizzes/{quizId}")
    public Quiz quiz(@PathVariable UUID quizId) {
        return quizzes.get(quizId);
    }

    @DeleteMapping("/quizzes/{quizId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteQuiz(@PathVariable UUID quizId) {
        quizzes.delete(quizId);
    }

    // ---- exports -----------------------------------------------------------------------------------

    /** format: pdf or docx for notes and QA reports (part: NOTES, MCQ or COMBINED); pdf, docx, csv or xlsx for a question bank;
     *  pdf, docx or xlsx for an SEO report; pptx or pdf for a presentation. */
    @GetMapping("/exports/versions/{versionId}")
    public ResponseEntity<byte[]> exportVersion(@PathVariable UUID versionId, @RequestParam String format,
                                                @RequestParam(defaultValue = "COMBINED") String part) {
        return file(exports.version(versionId, format, part));
    }

    @GetMapping("/exports/quizzes/{quizId}")
    public ResponseEntity<byte[]> exportQuiz(@PathVariable UUID quizId, @RequestParam String format,
                                             @RequestParam(defaultValue = "end") String answers) {
        return file(exports.quiz(quizId, format, answers));
    }

    private static ResponseEntity<byte[]> file(ExportFile f) {
        String encoded = URLEncoder.encode(f.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        String ascii = f.filename().replaceAll("[^A-Za-z0-9._-]", "_");
        return ResponseEntity.ok()
                .header("Content-Type", f.contentType())
                .header("Content-Disposition", "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .body(f.bytes());
    }
}
