-- Increment E7: the presentation studio.
--
-- A deck is an approvable resource per topic and notes level. Its slides are data (JSON in
-- the version), edited in the app and exported to PPTX and PDF by the document service.
-- Diagrams reuse note_figures (the browser's drawing, kept by source hash).
--
-- "No presentation before academic and SEO approval": a deck is made only from approved
-- notes, with the topic's QA report and SEO report approved. That rule is checked again
-- when a deck is approved, here in the database as well as in the application.

ALTER TABLE resources DROP CONSTRAINT resources_kind_check;
ALTER TABLE resources ADD CONSTRAINT resources_kind_check CHECK (kind IN ('SYLLABUS', 'TOPICS', 'NOTES', 'MCQ', 'QA', 'SEO', 'SLIDES'));
ALTER TABLE resources DROP CONSTRAINT ck_resources_shape;
ALTER TABLE resources ADD CONSTRAINT ck_resources_shape CHECK (
    (kind IN ('SYLLABUS', 'TOPICS') AND topic_id IS NULL AND level IS NULL)
 OR (kind IN ('NOTES', 'SLIDES') AND topic_id IS NOT NULL AND level IS NOT NULL)
 OR (kind IN ('MCQ', 'QA', 'SEO') AND topic_id IS NOT NULL AND level IS NULL));

ALTER TABLE analysis_jobs DROP CONSTRAINT analysis_jobs_kind_check;
ALTER TABLE analysis_jobs ADD CONSTRAINT analysis_jobs_kind_check CHECK (kind IN ('SYLLABUS', 'NOTES', 'MCQ', 'QA', 'SEO', 'SLIDES'));

-- A deck can only become Approved while the notes it was made from, the topic's QA report
-- and the topic's SEO report each have an approved version.
CREATE FUNCTION leenax_check_slides_gate() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    deck   resources%ROWTYPE;
    notes  integer;
    qa     integer;
    seo    integer;
BEGIN
    IF NEW.status = 'APPROVED' AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM NEW.status) THEN
        SELECT * INTO deck FROM resources WHERE id = NEW.resource_id;
        IF deck.kind = 'SLIDES' THEN
            SELECT count(*) INTO notes FROM resource_versions v JOIN resources r ON r.id = v.resource_id
             WHERE r.project_id = deck.project_id AND r.kind = 'NOTES' AND r.topic_id = deck.topic_id AND r.level = deck.level
               AND v.status = 'APPROVED';
            SELECT count(*) INTO qa FROM resource_versions v JOIN resources r ON r.id = v.resource_id
             WHERE r.project_id = deck.project_id AND r.kind = 'QA' AND r.topic_id = deck.topic_id AND v.status = 'APPROVED';
            SELECT count(*) INTO seo FROM resource_versions v JOIN resources r ON r.id = v.resource_id
             WHERE r.project_id = deck.project_id AND r.kind = 'SEO' AND r.topic_id = deck.topic_id AND v.status = 'APPROVED';
            IF notes = 0 OR qa = 0 OR seo = 0 THEN
                RAISE EXCEPTION 'A presentation needs approved notes, an approved academic QA report and an approved SEO report'
                    USING ERRCODE = 'LX017';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_resource_versions_slides_gate BEFORE INSERT OR UPDATE ON resource_versions
    FOR EACH ROW EXECUTE FUNCTION leenax_check_slides_gate();
