"""E3 end to end on real PostgreSQL with the exact SQL in ReviewStore.java and AnalysisStore.java."""
import _env
import psycopg, uuid, sys, re, json
DSN = _env.DSN
SRC = _env.SRC
ACT_SQL = re.search(r'ACT_SQL = """(.*?)""";', open(SRC+'access/ActorDataSource.java').read(), re.S).group(1).replace('?', '%s')
def const(path, name):
    return re.search(name + r' = """(.*?)""";', open(SRC+path).read(), re.S).group(1).replace('?', '%s')
def strs(path, *needles):
    src = open(SRC+path).read()
    hits = [m for m in re.findall(r'"((?:select|insert|update|delete)[^"]*)"', src) if all(n in m for n in needles)]
    assert len(hits) == 1, (needles, hits); return hits[0].replace('?', '%s')
def block(path, *needles):
    hits = [b for b in re.findall(r'"""(.*?)"""', open(SRC+path).read(), re.S) if all(n in b for n in needles)]
    assert len(hits) == 1, (needles, len(hits)); return hits[0].replace('?', '%s')
RS = 'approvals/ReviewStore.java'; AS = 'syllabus/AnalysisStore.java'
DEPENDENTS = const(RS, 'DEPENDENTS_SQL'); OUTDATE = const(RS, 'OUTDATE_SQL')
INS_RES = strs(RS, 'insert into resources'); LINK = strs(RS, 'insert into resource_links')
INS_VER = block(RS, 'insert into resource_versions'); SAVE = block(RS, 'update resource_versions set content')
MOVE = strs(RS, 'update resource_versions set status = ?'); EVENT = block(RS, 'insert into review_events')
EVENTS = block(RS, 'from review_events e join resource_versions')
CLAIM = const(AS, 'CLAIM_SQL'); READY = const(AS, 'READY_DOCS_SQL'); INS_JOB = strs(AS, 'insert into analysis_jobs')

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
cl.execute("insert into projects(id,name,kind) values (%s,%s,'EDUCATION')", (proj, 'DBMS '+tag))
cl.execute("insert into project_members(project_id,user_id,role,added_by) values (%s,%s,'REVIEWER',%s),(%s,%s,'VIEWER',%s)", (proj, rev, lect, proj, stu, lect))

# documents for the analysis picker: v1 and v2 read, v3 failed
d = uuid.uuid4(); cl.execute("insert into documents(id,project_id,title) values (%s,%s,'Handbook')", (d, proj))
vers = {}
for no, st in [(1, 'READY'), (2, 'READY'), (3, 'FAILED')]:
    v = uuid.uuid4(); vers[no] = v
    cl.execute("insert into document_versions(id,document_id,version_no,original_filename,content_type,size_bytes,sha256,storage_key) values (%s,%s,%s,'h.pdf','application/pdf',1,%s,'k')", (v, d, no, str(no)*64))
    w = conn('worker', lect)
    w.execute("insert into document_pages(version_id,page_no,method,text) values (%s,1,'TEXT',%s)", (v, 'x'*(100*no)))
    w.execute("update document_versions set status=%s where id=%s", (st, v))
rows = cs.execute(READY, (proj,)).fetchall()
check('picker offers the newest read version, with size', rows == [(d, 'Handbook', vers[2], 2, 1, 200)], rows)

# analysis job: queued by the lecturer, one at a time, claimed by the worker
j = uuid.uuid4(); cl.execute(INS_JOB, (j, proj, 'SYLLABUS', '', json.dumps({"documents": []})))
check('second analysis while one runs refused', fails(cl, INS_JOB, uuid.uuid4(), proj, 'SYLLABUS', '', '{}') == '23505')
check('viewer cannot start an analysis', fails(cs, INS_JOB, uuid.uuid4(), proj, 'SYLLABUS', '', '{}') == '42501')
check('worker claims it', j in [r[0] for r in sysc.execute(CLAIM, (5,)).fetchall()])

# syllabus v1, made by the worker as the lecturer (AI), sent for review, approved by the reviewer
w = conn('worker', lect)
syl = uuid.uuid4(); w.execute(INS_RES, (syl, proj, 'SYLLABUS', 'Syllabus', None, None))
s1 = uuid.uuid4(); w.execute(INS_VER, (s1, syl, 1, 'DRAFT', 'AI', '{"structure":[]}', 'a'*64, None, None))
w.execute(EVENT, (uuid.uuid4(), proj, syl, s1, 'CREATED', None, 'DRAFT', None, '{"origin":"AI"}'))
check('the AI draft is attributed to the lecturer', sysc.execute("select created_by from resource_versions where id=%s", (s1,)).fetchone()[0] == lect)
check('the worker cannot approve its own draft', fails(w, MOVE, 'APPROVED', s1, 'DRAFT') == 'LX011')
check('lecturer edits the draft', cl.execute(SAVE, ('{"structure":[1]}', 'b'*64, None, s1)).rowcount == 1)
check('lecturer submits', cl.execute(MOVE, ('IN_REVIEW', s1, 'DRAFT')).rowcount == 1)
check('editing in review does nothing', cl.execute(SAVE, ('{}', 'c'*64, None, s1)).rowcount == 0)
check('lecturer cannot approve own work', fails(cl, MOVE, 'APPROVED', s1, 'IN_REVIEW') == 'LX012')
check('reviewer approves', cr.execute(MOVE, ('APPROVED', s1, 'IN_REVIEW')).rowcount == 1)
cr.execute(EVENT, (uuid.uuid4(), proj, syl, s1, 'APPROVED', 'IN_REVIEW', 'APPROVED', 'Matches the handbook', '{}'))
check('approving twice does nothing', cr.execute(MOVE, ('APPROVED', s1, 'IN_REVIEW')).rowcount == 0)

# topics from the approved syllabus, approved too
top = uuid.uuid4(); cl.execute(INS_RES, (top, proj, 'TOPICS', 'Topics', None, None)); cl.execute(LINK, (top, syl))
t1 = uuid.uuid4(); cl.execute(INS_VER, (t1, top, 1, 'DRAFT', 'MANUAL', '{"nodes":[]}', 'd'*64, None, s1))
t2 = uuid.uuid4(); cl.execute(INS_VER, (t2, top, 2, 'DRAFT', 'EDIT', '{"nodes":[1]}', 'e'*64, t1, s1))
cl.execute(MOVE, ('ARCHIVED', t1, 'DRAFT')); cl.execute(MOVE, ('IN_REVIEW', t2, 'DRAFT')); cr.execute(MOVE, ('APPROVED', t2, 'IN_REVIEW'))
check('topics depend on the syllabus', cs.execute(DEPENDENTS, (syl,)).fetchall() == [(top,)])
check('a viewer still sees the dependency', len(cs.execute(DEPENDENTS, (syl,)).fetchall()) == 1)

# editing the approved syllabus outdates the topics
s2 = uuid.uuid4(); cl.execute(INS_VER, (s2, syl, 2, 'DRAFT', 'EDIT', '{"structure":[2]}', 'f'*64, s1, None))
changed = cl.execute(OUTDATE, (top,)).fetchall()
check('topics become Outdated, with their old status', changed == [(t2, 'APPROVED')], changed)
for vid, before in changed:
    cl.execute(EVENT, (uuid.uuid4(), proj, top, vid, 'OUTDATED', before, 'OUTDATED', 'Syllabus version 1 is being changed', '{}'))
check('archived topic versions are left alone', sysc.execute("select status from resource_versions where id=%s", (t1,)).fetchone()[0] == 'ARCHIVED')
check('the approved syllabus stays approved meanwhile', sysc.execute("select status from resource_versions where id=%s", (s1,)).fetchone()[0] == 'APPROVED')
check('outdated content is frozen', cl.execute(SAVE, ('{}', 'g'*64, s1, t2)).rowcount == 0)
check('viewer cannot outdate anything', cs.execute(OUTDATE, (top,)).fetchall() == [])

# approve with edits: reviewer archives the old approved one, adds an approved version, archives the reviewed one
cl.execute(MOVE, ('IN_REVIEW', s2, 'DRAFT'))
cr.execute(MOVE, ('ARCHIVED', s1, 'APPROVED'))
s3 = uuid.uuid4()
check('reviewer approves with edits', fails(cr, INS_VER, s3, syl, 3, 'APPROVED', 'REVIEWER', '{"structure":[3]}', 'h'*64, s2, None) is None)
check('the reviewed version is archived', cr.execute(MOVE, ('ARCHIVED', s2, 'IN_REVIEW')).rowcount == 1)
cr.execute(EVENT, (uuid.uuid4(), proj, syl, s3, 'APPROVED_WITH_EDITS', None, 'APPROVED', 'Fixed two titles', json.dumps({"changes": ["Renamed “A” to “B”"]})))

hist = cs.execute(EVENTS, (syl,)).fetchall()
check('the history shows every decision, newest first', [h[3] for h in hist] == ['APPROVED_WITH_EDITS', 'APPROVED', 'CREATED'], [h[3] for h in hist])
check('history names the people', hist[0][9] == 'Reviewer' and hist[2][9] == 'Lecturer')
check('history keeps the change list', json.loads(hist[0][7])['changes'] == ['Renamed “A” to “B”'])

sysc.execute("delete from projects where id=%s", (proj,))
sysc.execute("set session_replication_role = replica")
for u in (lect, rev, stu): sysc.execute("delete from users where id=%s", (u,))
print(f"E3 end to end: {ok} checks passed")
