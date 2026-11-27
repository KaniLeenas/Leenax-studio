-- Reusable agent and workflow configuration. Provider credentials remain in the
-- existing encrypted ai_accounts/api_credentials tables; no secrets are copied here.
CREATE TABLE ai_agents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    category VARCHAR(40) NOT NULL,
    instructions TEXT NOT NULL,
    account_id UUID REFERENCES ai_accounts(id) ON DELETE SET NULL,
    model VARCHAR(120) NOT NULL,
    temperature NUMERIC(3,2) NOT NULL DEFAULT 0.2 CHECK (temperature BETWEEN 0 AND 2),
    max_output_tokens INTEGER NOT NULL DEFAULT 2000 CHECK (max_output_tokens BETWEEN 1 AND 32000),
    input_schema JSONB NOT NULL DEFAULT '{"type":"object"}'::jsonb,
    output_schema JSONB NOT NULL DEFAULT '{"type":"object"}'::jsonb,
    tools JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(tools) = 'array'),
    permissions JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(permissions) = 'array'),
    timeout_seconds INTEGER NOT NULL DEFAULT 90 CHECK (timeout_seconds BETWEEN 5 AND 900),
    retry_count SMALLINT NOT NULL DEFAULT 1 CHECK (retry_count BETWEEN 0 AND 5),
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    created_by UUID NOT NULL DEFAULT leenax_uid() REFERENCES users(id) ON DELETE RESTRICT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (jsonb_typeof(input_schema) = 'object'),
    CHECK (jsonb_typeof(output_schema) = 'object')
);
CREATE INDEX idx_ai_agents_project_updated ON ai_agents(project_id, updated_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_ai_agents_creator_updated ON ai_agents(created_by, updated_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_ai_agents_category ON ai_agents(category, enabled) WHERE deleted_at IS NULL;

CREATE TABLE ai_agent_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id UUID NOT NULL REFERENCES ai_agents(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    configuration JSONB NOT NULL,
    changed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(agent_id, version)
);
CREATE INDEX idx_ai_agent_versions_history ON ai_agent_versions(agent_id, version DESC);

CREATE TABLE ai_agent_executions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id UUID REFERENCES ai_agents(id) ON DELETE SET NULL,
    agent_name VARCHAR(120) NOT NULL,
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    created_by UUID NOT NULL DEFAULT leenax_uid() REFERENCES users(id) ON DELETE RESTRICT,
    workflow_execution_id UUID,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','QUEUED','RUNNING','WAITING_APPROVAL','COMPLETED','FAILED','CANCELLED')),
    provider VARCHAR(30),
    model VARCHAR(120),
    input JSONB NOT NULL DEFAULT '{}'::jsonb,
    output JSONB,
    error_message VARCHAR(1000),
    input_tokens INTEGER,
    output_tokens INTEGER,
    estimated_cost NUMERIC(14,6),
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    retry_of UUID REFERENCES ai_agent_executions(id) ON DELETE SET NULL
);
CREATE INDEX idx_ai_agent_executions_user ON ai_agent_executions(created_by, created_at DESC);
CREATE INDEX idx_ai_agent_executions_project ON ai_agent_executions(project_id, created_at DESC);
CREATE INDEX idx_ai_agent_executions_status ON ai_agent_executions(status, created_at);
CREATE INDEX idx_ai_agent_executions_agent ON ai_agent_executions(agent_id, created_at DESC);

CREATE TABLE ai_agent_execution_logs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    execution_id UUID NOT NULL REFERENCES ai_agent_executions(id) ON DELETE CASCADE,
    level VARCHAR(10) NOT NULL CHECK (level IN ('INFO','WARN','ERROR')),
    stage VARCHAR(80) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_agent_execution_logs_order ON ai_agent_execution_logs(execution_id, created_at, id);

CREATE TABLE ai_agent_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_key VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    category VARCHAR(40) NOT NULL,
    configuration JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Tools are a allow-listed registry of application APIs. This table never holds
-- executable shell commands, arbitrary URLs, provider secrets, or filesystem paths.
CREATE TABLE ai_tool_registry (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tool_key VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    input_schema JSONB NOT NULL DEFAULT '{"type":"object"}'::jsonb,
    output_schema JSONB NOT NULL DEFAULT '{"type":"object"}'::jsonb,
    permission VARCHAR(40) NOT NULL,
    timeout_seconds INTEGER NOT NULL DEFAULT 30 CHECK (timeout_seconds BETWEEN 1 AND 300),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ai_workflows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    created_by UUID NOT NULL DEFAULT leenax_uid() REFERENCES users(id) ON DELETE RESTRICT,
    definition JSONB NOT NULL DEFAULT '{"nodes":[],"edges":[]}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    template BOOLEAN NOT NULL DEFAULT FALSE,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (jsonb_typeof(definition) = 'object')
);
CREATE INDEX idx_ai_workflows_project_updated ON ai_workflows(project_id, updated_at DESC);
CREATE INDEX idx_ai_workflows_creator_updated ON ai_workflows(created_by, updated_at DESC);

CREATE TABLE ai_workflow_executions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id UUID REFERENCES ai_workflows(id) ON DELETE SET NULL,
    workflow_name VARCHAR(160) NOT NULL,
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    created_by UUID NOT NULL DEFAULT leenax_uid() REFERENCES users(id) ON DELETE RESTRICT,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','QUEUED','RUNNING','WAITING_APPROVAL','PAUSED','COMPLETED','FAILED','CANCELLED')),
    current_node VARCHAR(120),
    resume_decision VARCHAR(16) CHECK (resume_decision IN ('APPROVE','REJECT','RESUME')),
    graph_thread_id VARCHAR(120) UNIQUE,
    input JSONB NOT NULL DEFAULT '{}'::jsonb,
    output JSONB,
    error_message VARCHAR(1000),
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_workflow_executions_user ON ai_workflow_executions(created_by, created_at DESC);
CREATE INDEX idx_ai_workflow_executions_project ON ai_workflow_executions(project_id, created_at DESC);
CREATE INDEX idx_ai_workflow_executions_status ON ai_workflow_executions(status, created_at);

CREATE TABLE ai_agent_memory (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category VARCHAR(24) NOT NULL CHECK (category IN ('CONVERSATION','PROJECT','WORKFLOW','AGENT_CONFIG','KNOWLEDGE')),
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    agent_id UUID REFERENCES ai_agents(id) ON DELETE CASCADE,
    created_by UUID NOT NULL DEFAULT leenax_uid() REFERENCES users(id) ON DELETE CASCADE,
    body JSONB NOT NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_agent_memory_scope ON ai_agent_memory(created_by, project_id, category, created_at DESC);

-- Use the same fail-closed user/project policy as the existing content records.
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['ai_agents','ai_agent_executions','ai_workflows','ai_workflow_executions','ai_agent_memory'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY read ON %I FOR SELECT USING (leenax_can_read(project_id, created_by))', t);
        EXECUTE format('CREATE POLICY add ON %I FOR INSERT WITH CHECK (leenax_can_write(project_id, created_by))', t);
        EXECUTE format('CREATE POLICY change ON %I FOR UPDATE USING (leenax_can_write(project_id, created_by)) WITH CHECK (leenax_can_write(project_id, created_by))', t);
        EXECUTE format('CREATE POLICY remove ON %I FOR DELETE USING (leenax_can_write(project_id, created_by))', t);
    END LOOP;
END $$;

ALTER TABLE ai_agent_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_agent_versions FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON ai_agent_versions FOR SELECT USING
    (EXISTS (SELECT 1 FROM ai_agents a WHERE a.id = agent_id));
CREATE POLICY write ON ai_agent_versions FOR ALL USING
    (leenax_is_super() OR EXISTS (SELECT 1 FROM ai_agents a WHERE a.id = agent_id AND leenax_can_write(a.project_id, a.created_by)))
    WITH CHECK (leenax_is_super() OR EXISTS (SELECT 1 FROM ai_agents a WHERE a.id = agent_id AND leenax_can_write(a.project_id, a.created_by)));

ALTER TABLE ai_agent_execution_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_agent_execution_logs FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON ai_agent_execution_logs FOR SELECT USING
    (EXISTS (SELECT 1 FROM ai_agent_executions e WHERE e.id = execution_id));
CREATE POLICY write ON ai_agent_execution_logs FOR ALL USING
    (leenax_is_super() OR EXISTS (SELECT 1 FROM ai_agent_executions e WHERE e.id = execution_id AND leenax_can_write(e.project_id, e.created_by)))
    WITH CHECK (leenax_is_super() OR EXISTS (SELECT 1 FROM ai_agent_executions e WHERE e.id = execution_id AND leenax_can_write(e.project_id, e.created_by)));

ALTER TABLE ai_tool_registry ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_tool_registry FORCE ROW LEVEL SECURITY;
CREATE POLICY read ON ai_tool_registry FOR SELECT USING (leenax_is_super() OR coalesce(leenax_mode() = 'user', false));
CREATE POLICY write ON ai_tool_registry FOR ALL USING (leenax_is_super()) WITH CHECK (leenax_is_super());

INSERT INTO ai_tool_registry(tool_key,name,description,permission,timeout_seconds) VALUES
('document.extract','Document extraction','Extract text and page references through the existing document service','DOCUMENT_READ',120),
('research.search','Research search','Run the configured SEO/research provider flow','RESEARCH',60),
('youtube.research','YouTube research','Use the connected YouTube research APIs','RESEARCH',60),
('image.generate','Generate image','Use the existing image provider and media storage','IMAGE_GENERATE',300),
('voice.generate','Generate voice','Use the existing configured voice provider','VOICE_GENERATE',300),
('subtitle.generate','Generate subtitles','Use the existing subtitle pipeline','SUBTITLE_EDIT',120),
('video.render','Render video','Queue a render through the existing editor renderer','VIDEO_RENDER',900),
('media.read','Read permitted media metadata','Read metadata for media authorized to the current project','MEDIA_READ',30),
('content.write','Save approved content','Save generated outputs through existing project content APIs','CONTENT_WRITE',60)
ON CONFLICT (tool_key) DO NOTHING;

INSERT INTO ai_agent_templates(template_key,name,description,category,configuration) VALUES
('pdf-analysis','PDF Analysis Agent','Analyze extracted PDF text with page references.','DOCUMENT','{"tools":["document.extract"]}'),
('docx-analysis','DOCX Analysis Agent','Analyze Word document content.','DOCUMENT','{"tools":["document.extract"]}'),
('pptx-analysis','PPTX Analysis Agent','Analyze presentation text and slide order.','DOCUMENT','{"tools":["document.extract"]}'),
('chapter-extraction','Chapter Extraction Agent','Identify chapters and headings.','DOCUMENT','{"tools":["document.extract"]}'),
('topic-identification','Topic Identification Agent','Extract hierarchical topics and subtopics.','EDUCATION','{"tools":["document.extract"]}'),
('curriculum-mapping','Curriculum Mapping Agent','Map topics to learning objectives and curriculum outcomes.','EDUCATION','{}'),
('research','Research Agent','Gather relevant research and source references.','RESEARCH','{"tools":["research.search"]}'),
('web-search','Web Search Agent','Search approved web research sources.','RESEARCH','{"tools":["research.search"]}'),
('youtube-research','YouTube Research Agent','Research YouTube topics and public metadata.','RESEARCH','{"tools":["youtube.research"]}'),
('keyword-analysis','Keyword Analysis Agent','Analyze topic keywords for learning and search intent.','RESEARCH','{"tools":["research.search"]}'),
('fact-verification','Fact Verification Agent','Check claims against supplied references and flag uncertainty.','RESEARCH','{"tools":["research.search"]}'),
('source-citation','Source Citation Agent','Format citations tied to provided source references.','RESEARCH','{}'),
('educational-script','Educational Script Agent','Create a structured lesson script.','CONTENT','{"tools":["content.write"]}'),
('tamil-translation','Tamil Translation Agent','Translate approved material into Tamil.','CONTENT','{}'),
('english-improvement','English Improvement Agent','Improve clarity and grammar in English content.','CONTENT','{}'),
('mcq-generation','MCQ Generation Agent','Create structured multiple-choice questions.','CONTENT','{"tools":["content.write"]}'),
('quiz-evaluation','Quiz Evaluation Agent','Evaluate answers and explain results.','CONTENT','{}'),
('educational-summary','Educational Summary Agent','Produce a concise study summary.','CONTENT','{}'),
('storyboard','Storyboard Agent','Create a scene-by-scene lesson plan.','VIDEO','{"tools":["content.write"]}'),
('image-prompt','Image Prompt Agent','Create scene-aligned image prompts.','VIDEO','{}'),
('image-generation','Image Generation Agent','Generate an individual scene image.','VIDEO','{"tools":["image.generate"]}'),
('image-quality','Image Quality Analysis Agent','Check supplied image metadata and prompt alignment.','VIDEO','{"tools":["media.read"]}'),
('voice-generation','Voice Generation Agent','Prepare narration and queue configured speech synthesis.','VIDEO','{"tools":["voice.generate"]}'),
('subtitle-generation','Subtitle Generation Agent','Create timed subtitle segments.','VIDEO','{"tools":["subtitle.generate"]}'),
('video-editing','Video Editing Agent','Prepare an editable timeline plan.','VIDEO','{"tools":["content.write"]}'),
('ffmpeg-render','FFmpeg Rendering Agent','Validate and submit existing supported render jobs.','VIDEO','{"tools":["video.render"]}'),
('video-qa','Video Quality Assurance Agent','Review render metadata and configured QA reports.','VIDEO','{"tools":["media.read"]}'),
('youtube-seo','YouTube SEO Agent','Prepare YouTube title, description, tags and chapters.','PUBLISH','{"tools":["youtube.research"]}'),
('youtube-thumbnail','YouTube Thumbnail Agent','Prepare a thumbnail prompt for the existing Thumbnail Studio.','PUBLISH','{"tools":["image.generate"]}'),
('publishing-preparation','Publishing Preparation Agent','Prepare metadata and a draft for human review.','PUBLISH','{}'),
('youtube-upload','YouTube Upload Agent','Submit only an explicitly approved YouTube upload.','PUBLISH','{}'),
('publishing-verification','Publishing Verification Agent','Verify status of an existing published item.','PUBLISH','{}')
ON CONFLICT (template_key) DO NOTHING;
