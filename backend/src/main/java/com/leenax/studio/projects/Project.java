package com.leenax.studio.projects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "projects")
public class Project {

    public enum Status { DRAFT, IN_PROGRESS, COMPLETED, ARCHIVED }

    public enum AspectRatio { LANDSCAPE, PORTRAIT, SQUARE }

    public enum Language { EN, TA }

    /** A creator project (ideas, scripts, storyboards) or a university subject (documents, notes, MCQs, approvals). */
    public enum Kind { CREATOR, EDUCATION }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "aspect_ratio", nullable = false, length = 10)
    private AspectRatio aspectRatio = AspectRatio.LANDSCAPE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private Language language = Language.EN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Kind kind = Kind.CREATOR;

    @Column(name = "subject_name", length = 200)
    private String subjectName;

    @Column(name = "module_code", length = 40)
    private String moduleCode;

    @Column(name = "academic_year", length = 20)
    private String academicYear;

    @Column(length = 20)
    private String semester;

    @Column(length = 200)
    private String university;

    @Column(length = 200)
    private String department;

    /** Academic stages: the person who wrote a version may not approve it. */
    @Column(name = "reviewer_not_author", nullable = false)
    private boolean reviewerNotAuthor = true;

    /** Set by the database from the acting person (V9); never written by the app. */
    @Column(name = "created_by", insertable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Project() {
    }

    public Project(String name) {
        this.name = name;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public AspectRatio getAspectRatio() { return aspectRatio; }
    public void setAspectRatio(AspectRatio aspectRatio) { this.aspectRatio = aspectRatio; }
    public Language getLanguage() { return language; }
    public void setLanguage(Language language) { this.language = language; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public String getSubjectName() { return subjectName; }
    public void setSubjectName(String v) { this.subjectName = v; }
    public String getModuleCode() { return moduleCode; }
    public void setModuleCode(String v) { this.moduleCode = v; }
    public String getAcademicYear() { return academicYear; }
    public void setAcademicYear(String v) { this.academicYear = v; }
    public String getSemester() { return semester; }
    public void setSemester(String v) { this.semester = v; }
    public String getUniversity() { return university; }
    public void setUniversity(String v) { this.university = v; }
    public String getDepartment() { return department; }
    public void setDepartment(String v) { this.department = v; }
    public boolean isReviewerNotAuthor() { return reviewerNotAuthor; }
    public void setReviewerNotAuthor(boolean v) { this.reviewerNotAuthor = v; }
    public UUID getCreatedBy() { return createdBy; }
}
