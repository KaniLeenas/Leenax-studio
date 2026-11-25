"""V16 and E8 on real PostgreSQL with the exact SQL in VideoStore.java: lesson and quiz videos,
their sources (LX018) and "a rendered video passes validation before it can be approved" (LX019)."""
import _env
import psycopg, uuid, sys, re, json
DSN = _env.DSN
SRC = _env.SRC
ACT_SQL = re.search(r'ACT_SQL = """(.*?)""";', open(SRC+'access/ActorDataSource.java').read(), re.S).group(1).replace('?', '%s')
def text(path): return open(SRC+path).read()
def strs(path, *needles):
    hits = [m for m in re.findall(r'"((?:select|insert|update|delete)[^"]*)"', text(path)) if all(n in m for n in needles)]
    assert len(hits) == 1, (needles, hits); return hits[0].replace('?', '%s')
def joined(path, *needles):
    runs = [''.join(re.findall(r'"([^"]*)"', m)) for m in re.findall(r'"[^"]*"(?:\s*\+\s*"[^"]*")+', text(path))]
    hits = [r for r in runs if all(n in r for n in needles)]
    assert len(hits) == 1, (needles, hits); return hits[0].replace('?', '%s')

V = 'videos/VideoStore.java'; RS = 'approvals/ReviewStore.java'; SS = 'slides/SlidesStore.java'
RENDERS = joined(V, 'from video_renders vr join render_jobs'); RECORD = strs(V, 'insert into video_renders'); FIGURE = strs(V, 'from note_figures')
GATE_CONTENT = joined(V, 'content_hash = ?', 'resource_versions')
APPROVED = joined(SS, "v.status = 'APPROVED'", 'is not distinct from')
INS_RES = strs(RS, 'insert into resources'); MOVE = strs(RS, 'update resource_versions set status = ?')
INS_VER = re.search(r'"""(\s*insert into resource_versions.*?)"""', text(RS), re.S).group(1).replace('?', '%s')

def conn(mode='system', uid=None):
    c = psycopg.connect(DSN, autocommit=True); u = str(uid) if uid else None
    c.execute(ACT_SQL, (mode, u if mode != 'user' else None, mode, mode, mode, u, None)); return c
ok = 0
def check(name, cond, got=None):
    global ok
    if not cond: sys.exit(f"FAIL {name}: {got}")
    ok += 1
def fails(c, sql, *p):
    try: c.execute(sql, p); return None
    except psycopg.Error as e: return e.sqlstate

sysc = conn()
admin = sysc.execute("select id from users where role='ADMIN' and status='ACTIVE' limit 1").fetchone()[0]
sysc.execute("update users set status='DEACTIVATED' where id <> %s and status <> 'DEACTIVATED'", (admin,))
tag = uuid.uuid4().hex[:6]
mk = lambda n: sysc.execute("insert into users(email,display_name,password_hash) values (%s,%s,'x') returning id", (f"{n}-{tag}@t.lk", n)).fetchone()[0]
lect, rev, stu = mk('Lecturer'), mk('Reviewer'), mk('Student')
cl, cr, cs = conn('user', lect), conn('user', rev), conn('user', stu)
proj = uuid.uuid4()
cl.execute("insert into projects(id,name,kind) values (%s,%s,'EDUCATION')", (proj, 'Videos '+tag))
cl.execute("insert into project_members(project_id,user_id,role,added_by) values (%s,%s,'REVIEWER',%s),(%s,%s,'VIEWER',%s)", (proj, rev, lect, proj, stu, lect))

# shapes
check('a lesson video needs a level', fails(cl, INS_RES, uuid.uuid4(), proj, 'LESSON_VIDEO', 'x', 't1', None) == '23514')
check('a quiz video has no level', fails(cl, INS_RES, uuid.uuid4(), proj, 'QUIZ_VIDEO', 'x', 't1', 'BEGINNER') == '23514')
lesson, quiz = uuid.uuid4(), uuid.uuid4()
cl.execute(INS_RES, (lesson, proj, 'LESSON_VIDEO', 'Keys: beginner lesson video', 't1', 'BEGINNER'))
cl.execute(INS_RES, (quiz, proj, 'QUIZ_VIDEO', 'Keys: quiz video', 't1', None))
check('one quiz video per topic', fails(cl, INS_RES, uuid.uuid4(), proj, 'QUIZ_VIDEO', 'dup', 't1', None) == '23505')
check('a narration job is allowed', fails(cl, "insert into analysis_jobs(id,project_id,kind,target,input) values (%s,%s,'NARRATION',%s,'{}')", uuid.uuid4(), proj, str(lesson)) is None)
check('one narration at a time per video', fails(cl, "insert into analysis_jobs(id,project_id,kind,target,input) values (%s,%s,'NARRATION',%s,'{}')", uuid.uuid4(), proj, str(lesson)) == '23505')

def version(res, status='DRAFT', h=None):
    v = uuid.uuid4(); h = h or uuid.uuid4().hex * 2
    cl.execute(INS_VER, (v, res, 1 + cl.execute("select count(*) from resource_versions where resource_id=%s", (res,)).fetchone()[0], status, 'MANUAL', '{"kind":"QUIZ"}', h, None, None))
    return v, h
def approve_source(kind, level):
    r = uuid.uuid4(); cl.execute(INS_RES, (r, proj, kind, kind, 't1', level))
    v, _ = version(r); cl.execute(MOVE, ('IN_REVIEW', v, 'DRAFT'))
    e = fails(cr, MOVE, 'APPROVED', v, 'IN_REVIEW'); assert e is None, (kind, e); return r, v

qv, qh = version(quiz); cl.execute(MOVE, ('IN_REVIEW', qv, 'DRAFT'))
check('no approved MCQs or SEO: LX018', fails(cr, MOVE, 'APPROVED', qv, 'IN_REVIEW') == 'LX018')
approve_source('MCQ', None)
check('MCQs without SEO: LX018', fails(cr, MOVE, 'APPROVED', qv, 'IN_REVIEW') == 'LX018')
approve_source('SEO', None)
check('sources approved but nothing rendered: LX019', fails(cr, MOVE, 'APPROVED', qv, 'IN_REVIEW') == 'LX019')

# a render of this version
tl = uuid.uuid4()
cl.execute("insert into timelines (id, project_id, name, width, height, fps, document) values (%s,%s,'quiz',1080,1920,30,'{}')", (tl, proj))
def render_job(status, validation, media=None):
    j = uuid.uuid4()
    cl.execute("insert into render_jobs (id, timeline_id, timeline_name, project_id, status, settings, document, validation, output_media_id) values (%s,%s,'quiz',%s,%s,'{}','{}',%s,%s)",
               (j, tl, proj, status, json.dumps(validation) if validation else None, media)); return j
def asset():
    a = uuid.uuid4()
    cl.execute("insert into media_assets(id,project_id,kind,original_filename,content_type,size_bytes,checksum_sha256,storage_key) values (%s,%s,'VIDEO','v.mp4','video/mp4',1,'x',%s)", (a, proj, 'k/' + a.hex)); return a

failed = render_job('FAILED', {'ok': False, 'problems': ['The sound is silent']})
cl.execute(RECORD, (uuid.uuid4(), proj, quiz, qv, qh, failed, tl))
check('a render that failed its checks: LX019', fails(cr, MOVE, 'APPROVED', qv, 'IN_REVIEW') == 'LX019')
other = render_job('COMPLETED', {'ok': True}, asset())
cl.execute(RECORD, (uuid.uuid4(), proj, quiz, qv, 'f' * 64, other, tl))
check('a passing render of other content: LX019', fails(cr, MOVE, 'APPROVED', qv, 'IN_REVIEW') == 'LX019')
nofile = render_job('COMPLETED', {'ok': True})
cl.execute(RECORD, (uuid.uuid4(), proj, quiz, qv, qh, nofile, tl))
check('a passing render without its file: LX019', fails(cr, MOVE, 'APPROVED', qv, 'IN_REVIEW') == 'LX019')
check('a viewer can\'t record renders', fails(cs, RECORD, uuid.uuid4(), proj, quiz, qv, qh, render_job('QUEUED', None), tl) == '42501')
good = render_job('COMPLETED', {'ok': True, 'found': {'width': 1080, 'height': 1920}}, asset())
cl.execute(RECORD, (uuid.uuid4(), proj, quiz, qv, qh, good, tl))
rows = cs.execute(RENDERS, (quiz,)).fetchall()
check('renders are listed newest first, for viewers too', rows[0][0] == good and len(rows) == 4, rows)
check('with its status, check and timeline', rows[0][4] == 'COMPLETED' and json.loads(rows[0][8])['ok'] is True and rows[0][3] == tl)
check('a render can be recorded only once', fails(cl, RECORD, uuid.uuid4(), proj, quiz, qv, qh, good, tl) == '23505')
check('a passing render of this content: approved', fails(cr, MOVE, 'APPROVED', qv, 'IN_REVIEW') is None)
check('the gate reads this version by its hash', json.loads(cs.execute(GATE_CONTENT, (quiz, qh)).fetchone()[0])['kind'] == 'QUIZ')
check('approving with edits makes new content with no render', fails(cr, INS_VER, uuid.uuid4(), quiz, 9, 'APPROVED', 'REVIEWER', '{}', 'e' * 64, qv, None) == 'LX019')

# a lesson video needs the presentation for its level
lv, lh = version(lesson); cl.execute(MOVE, ('IN_REVIEW', lv, 'DRAFT'))
lr = render_job('COMPLETED', {'ok': True}, asset()); cl.execute(RECORD, (uuid.uuid4(), proj, lesson, lv, lh, lr, tl))
approve_source('QA', None); approve_source('NOTES', 'ADVANCED'); approve_source('NOTES', 'BEGINNER')
approve_source('SLIDES', 'ADVANCED')
check('another level\'s presentation doesn\'t count: LX018', fails(cr, MOVE, 'APPROVED', lv, 'IN_REVIEW') == 'LX018')
approve_source('SLIDES', 'BEGINNER')
check('with the presentation approved and a passing render: approved', fails(cr, MOVE, 'APPROVED', lv, 'IN_REVIEW') is None)
check('the approved lesson video is found by topic and level', cl.execute(APPROVED, (proj, 'LESSON_VIDEO', 't1', 'BEGINNER')).fetchone()[0] == lv)

# other kinds are untouched
nr = uuid.uuid4(); cl.execute(INS_RES, (nr, proj, 'NOTES', 'n', 't9', 'BEGINNER')); nv, _ = version(nr); cl.execute(MOVE, ('IN_REVIEW', nv, 'DRAFT'))
check('notes are approved without renders', fails(cr, MOVE, 'APPROVED', nv, 'IN_REVIEW') is None)

# drawings for slides, as for the export
import hashlib
src = "flowchart LR\n A-->B"; h = hashlib.sha256(src.encode()).hexdigest()
cl.execute("insert into note_figures (project_id, kind, source_hash, svg) values (%s,'MERMAID',%s,'<svg/>') on conflict do nothing", (proj, h))
check('a drawing is found by its source', cs.execute(FIGURE, (proj, 'MERMAID', h)).fetchone()[0] == '<svg/>')
check('Google Cloud TTS is listed as a voice model', sysc.execute("select capability from ai_models where provider='GOOGLE_CLOUD_TTS' and model_code='cloud-tts'").fetchone()[0] == 'VOICE')

sysc.execute("delete from projects where id=%s", (proj,))
sysc.execute("set session_replication_role = replica")
sysc.execute("delete from media_assets where project_id is null and storage_key like 'k/%%'")
for u in (lect, rev, stu): sysc.execute("delete from users where id=%s", (u,))
print(f"E8 (V16) end to end: {ok} checks passed")
