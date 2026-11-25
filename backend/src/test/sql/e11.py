"""E11 on real PostgreSQL: topic analysis and the automatic steps after it (V19). Approving the
topic list queues an analysis per teaching unit; approving an analysis queues the notes; the
questions and the presentation are queued once what they need is approved; nothing that exists
is replaced; the jobs belong to the person whose approval started them, who is told when they
finish. Also the LX024 gate, who may change the settings, and the SQL in TopicAnalysisService."""
import _env
import psycopg, uuid, sys, re, json

DSN = _env.DSN
SRC = _env.SRC
ACT_SQL = re.search(r'ACT_SQL = """(.*?)""";', open(SRC + 'access/ActorDataSource.java').read(), re.S).group(1).replace('?', '%s')
def text(path): return open(SRC + path).read()
def block(path, name):
    m = re.search(name + r' = """(.*?)""";', text(path), re.S); assert m, name; return m.group(1).replace('?', '%s')

RS = 'approvals/ReviewStore.java'; TA = 'topicanalysis/TopicAnalysisService.java'
INS_RES = re.findall(r'"(insert into resources[^"]*)"', text(RS))[0].replace('?', '%s')
MOVE = re.findall(r'"(update resource_versions set status = \?[^"]*)"', text(RS))[0].replace('?', '%s')
INS_VER = re.search(r'"""(\s*insert into resource_versions.*?)"""', text(RS), re.S).group(1).replace('?', '%s')
EVENT = re.search(r'"""(\s*insert into review_events.*?)"""', text(RS), re.S).group(1).replace('?', '%s')
SETTINGS, SAVE = block(TA, 'SETTINGS'), block(TA, 'SAVE')
COUNTS = block('dashboard/EducationDashboard.java', 'COUNTS')
LIST = block('notifications/NotificationController.java', 'LIST')

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
owner, reviewer, editor, viewer = mk('Owner'), mk('Reviewer'), mk('Editor'), mk('Viewer')
co, cr, ce, cv = conn('user', owner), conn('user', reviewer), conn('user', editor), conn('user', viewer)
proj = uuid.uuid4()
co.execute("insert into projects(id,name,kind) values (%s,%s,'EDUCATION')", (proj, 'Databases ' + tag))
co.execute("insert into project_members(project_id,user_id,role,added_by) values (%s,%s,'REVIEWER',%s),(%s,%s,'EDITOR',%s),(%s,%s,'VIEWER',%s)",
           (proj, reviewer, owner, proj, editor, owner, proj, viewer, owner))
acct = uuid.uuid4()
sysc.execute("insert into ai_accounts(id,provider,name,status) values (%s,'OPENAI',%s,'ACTIVE')", (acct, 'Lab ' + tag))

def resource(kind, topic=None, level=None, by=ce, project=None):
    r = uuid.uuid4(); by.execute(INS_RES, (r, project or proj, kind, kind.title(), topic, level)); return r
def draft(r, content, by=ce):
    v = uuid.uuid4(); n = 1 + by.execute("select count(*) from resource_versions where resource_id=%s", (r,)).fetchone()[0]
    by.execute(INS_VER, (v, r, n, 'DRAFT', 'MANUAL', json.dumps(content), uuid.uuid4().hex * 2, None, None)); return v
def submit(r, v, by=ce, project=None):
    by.execute(MOVE, ('IN_REVIEW', v, 'DRAFT')); by.execute(EVENT, (uuid.uuid4(), project or proj, r, v, 'SUBMITTED', 'DRAFT', 'IN_REVIEW', None, '{}'))
def decide(r, v, by=cr, project=None):
    e = fails(by, MOVE, 'APPROVED', v, 'IN_REVIEW')
    if e is None: by.execute(EVENT, (uuid.uuid4(), project or proj, r, v, 'APPROVED', 'IN_REVIEW', 'APPROVED', None, '{}'))
    return e
def approve(kind, topic=None, level=None, content=None, r=None):
    r = r or resource(kind, topic, level); v = draft(r, content or {'kind': kind}); submit(r, v)
    e = decide(r, v); assert e is None, (kind, topic, e); return r, v
def jobs(kind=None):
    q = "select kind, target, input, auto, created_by, status from analysis_jobs where project_id=%s" + (" and kind=%s" if kind else "") + " order by created_at, target"
    return sysc.execute(q, (proj, kind) if kind else (proj,)).fetchall()
def save(c, enabled, account, model, level='INTERMEDIATE', mcq=20, minutes=8, notes=True, mcq_on=True, slides=True, project=None):
    return fails(c, SAVE, project or proj, enabled, account, model, level, mcq, minutes, notes, mcq_on, slides)

# ---- the settings --------------------------------------------------------------------------------------
check('a viewer can\'t change the automation', save(cv, True, acct, 'gpt-x') == '42501')
check('automation needs an account and a model', save(ce, True, None, None) == '23514')
check('an editor turns it on', save(ce, True, acct, 'gpt-x') is None)
s = cv.execute(SETTINGS, (proj,)).fetchone()
check('everyone in the project sees the settings, with the account\'s name and who set them',
      s[0] is True and s[2] == 'Lab ' + tag and s[3] == 'gpt-x' and s[5] == 20 and s[10] == 'Editor', s)
check('questions are 4 to 30', save(ce, True, acct, 'gpt-x', mcq=31) == '23514')
check('the video is 3 to 20 minutes', save(ce, True, acct, 'gpt-x', minutes=2) == '23514')
check('the reviewer can\'t change it (not an editor)',
      cr.execute("update topic_automation set model='other' where project_id=%s", (proj,)).rowcount == 0)
check('nobody can call the job function directly',
      fails(ce, "select leenax_auto_job(%s, 'NOTES', 'x', '{}'::jsonb)", proj) == '42501')

# ---- approving the topic list starts the analyses ----------------------------------------------------------
nodes = {'nodes': [
    {'id': 'ch1', 'title': 'Normalisation', 'children': [{'id': 't1', 'title': 'First normal form'}, {'id': 't2', 'title': 'Left out', 'selected': False}]},
    {'id': 'ch2', 'title': 'Transactions'},
    {'id': 'ch3', 'title': 'Not taught', 'selected': False, 'children': [{'id': 't9', 'title': 'Hidden'}]}]}
top, tv = approve('TOPICS', content=nodes)
a = jobs('TOPIC_ANALYSIS')
check('one analysis is queued per teaching unit: a selected topic, or a chapter without selected topics',
      sorted(x[1] for x in a) == ['ch2', 't1'], a)
check('the jobs say they started automatically, and belong to whoever approved the list',
      all(x[3] is True and x[4] == reviewer and x[5] == 'QUEUED' for x in a), a)
check('they carry the account, model and plan sizes',
      all(x[2]['accountId'] == str(acct) and x[2]['model'] == 'gpt-x' and x[2]['mcqCount'] == 20 and x[2]['videoMinutes'] == 8 and x[2]['level'] == 'INTERMEDIATE' for x in a), a)
check('the same function picks the units the Java page lists',
      [r[0] for r in sysc.execute("select topic_id from leenax_analysis_topics(%s::jsonb)", (json.dumps(nodes),)).fetchall()] == ['t1', 'ch2'])

tv2 = draft(top, nodes); submit(top, tv2); decide(top, tv2)
check('approving the list again doesn\'t queue the same analyses twice', len(jobs('TOPIC_ANALYSIS')) == 2, jobs('TOPIC_ANALYSIS'))

# the worker finishes one, as the job's owner: the approver is told
job1 = sysc.execute("select id from analysis_jobs where project_id=%s and target='t1'", (proj,)).fetchone()[0]
w = conn('worker', reviewer)
w.execute("update analysis_jobs set status='RUNNING' where id=%s", (job1,))
w.execute("update analysis_jobs set status='SUCCEEDED', finished_at=now() where id=%s", (job1,))
n = [x for x in cr.execute(LIST, (False, 50)).fetchall() if x[3] == 'JOB_DONE']
check('the reviewer is told the automatic analysis finished, even though the worker finished it',
      len(n) == 1 and 'started automatically' in (n[0][4] + (n[0][5] or '')) and n[0][6] == f'/projects/{proj}/analysis', n)
check('nobody else is told', not any(x[3] == 'JOB_DONE' for x in ce.execute(LIST, (False, 50)).fetchall()))
w.execute("update analysis_jobs set status='SUCCEEDED', finished_at=now() where project_id=%s and target='ch2'", (proj,))

# ---- the gate ------------------------------------------------------------------------------------------------------
p2 = uuid.uuid4()
co.execute("insert into projects(id,name,kind) values (%s,%s,'EDUCATION')", (p2, 'No topics ' + tag))
co.execute("insert into project_members(project_id,user_id,role,added_by) values (%s,%s,'REVIEWER',%s),(%s,%s,'EDITOR',%s)",
           (p2, reviewer, owner, p2, editor, owner))
ra = resource('TOPIC_ANALYSIS', 't1', project=p2); va = draft(ra, {'topicId': 't1'}); submit(ra, va, project=p2)
check('an analysis can\'t be approved without an approved topic list (LX024)', decide(ra, va, project=p2) == 'LX024')
check('a topic analysis has a topic and no level', fails(ce, INS_RES, uuid.uuid4(), proj, 'TOPIC_ANALYSIS', 'x', 't1', 'BEGINNER') is not None)

# ---- approving an analysis starts the notes ----------------------------------------------------------------------
plan = {'topicId': 't1', 'videoPlan': {'scenes': [{'title': s} for s in 'abcde']}}
an1, av1 = approve('TOPIC_ANALYSIS', 't1', content=plan)
nj = jobs('NOTES')
check('approving the analysis queues the notes at the chosen level, as the approver',
      [(x[1], x[3], x[4]) for x in nj] == [('t1:INTERMEDIATE', True, reviewer)] and nj[0][2]['level'] == 'INTERMEDIATE', nj)
check('…and nothing else yet: the questions wait for the notes', jobs('MCQ') == [] and jobs('SLIDES') == [])

# ---- approving the notes starts the questions -------------------------------------------------------------------
approve('NOTES', 't1', 'BEGINNER')
check('notes at another level don\'t start the questions', jobs('MCQ') == [])
approve('NOTES', 't1', 'INTERMEDIATE')
mj = jobs('MCQ')
check('notes at the chosen level do: the planned number of questions',
      len(mj) == 1 and mj[0][1] == 't1' and mj[0][2]['count'] == 20 and mj[0][2]['notesLevel'] == 'INTERMEDIATE' and mj[0][3], mj)
check('the presentation waits for QA and SEO', jobs('SLIDES') == [])

# ---- QA and SEO approved: the presentation ---------------------------------------------------------------------------
approve('MCQ', 't1', content={'questions': []})
approve('QA', 't1')
check('QA alone isn\'t enough', jobs('SLIDES') == [])
approve('SEO', 't1')
sj = jobs('SLIDES')
check('with QA and SEO approved, the presentation is queued from the video plan (5 scenes + 4 slides)',
      len(sj) == 1 and sj[0][1] == 't1:INTERMEDIATE' and sj[0][2]['slides'] == 9 and sj[0][2]['style'] == 'LECTURE', sj)

# ---- nothing that exists is replaced; approval with edits counts too ----------------------------------------------------
resource('MCQ', 'ch2')
an2 = resource('TOPIC_ANALYSIS', 'ch2'); v_ = draft(an2, {'topicId': 'ch2'}); submit(an2, v_)
cr.execute(MOVE, ('ARCHIVED', v_, 'IN_REVIEW'))
vrev = uuid.uuid4()
cr.execute(INS_VER, (vrev, an2, 2, 'APPROVED', 'REVIEWER', json.dumps({'topicId': 'ch2'}), uuid.uuid4().hex * 2, v_, None))
check('an analysis approved with the reviewer\'s edits (inserted approved) starts the notes too',
      any(x[1] == 'ch2:INTERMEDIATE' for x in jobs('NOTES')), jobs('NOTES'))
approve('NOTES', 'ch2', 'INTERMEDIATE')
check('a topic that already has a question bank doesn\'t get another', not any(x[1] == 'ch2' for x in jobs('MCQ')), jobs('MCQ'))

# ---- turned off: nothing starts ----------------------------------------------------------------------------------------
check('the editor turns automation off', save(ce, False, acct, 'gpt-x') is None)
nodes['nodes'].append({'id': 'ch4', 'title': 'Indexing'})
tv3 = draft(top, nodes); submit(top, tv3); decide(top, tv3)
check('with automation off, a new topic isn\'t analysed', not any(x[1] == 'ch4' for x in jobs('TOPIC_ANALYSIS')))

# ---- the dashboard ------------------------------------------------------------------------------------------------------
c = cv.execute(COUNTS).fetchone()
check('the dashboard counts analyses and approved analyses', c[17] == 2 and c[18] == 2, c[17:])

print(f"E11 (V19) topic analysis and automation: {ok} checks passed")
