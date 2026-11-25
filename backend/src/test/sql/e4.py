"""E4 end to end on real PostgreSQL with the exact SQL in ReviewStore, AnalysisStore, CommentService, ExportService and QuizService."""
import _env
import psycopg, uuid, sys, re, json
DSN = _env.DSN
SRC = _env.SRC
ACT_SQL = re.search(r'ACT_SQL = """(.*?)""";', open(SRC+'access/ActorDataSource.java').read(), re.S).group(1).replace('?', '%s')
def text(path): return open(SRC+path).read()
def const(path, name):
    return re.search(name + r' = """(.*?)""";', text(path), re.S).group(1).replace('?', '%s')
def strs(path, *needles):
    hits = [m for m in re.findall(r'"((?:select|insert|update|delete)[^"]*)"', text(path)) if all(n in m for n in needles)]
    assert len(hits) == 1, (needles, hits); return hits[0].replace('?', '%s')
def block(path, *needles):
    hits = [b for b in re.findall(r'"""(.*?)"""', text(path), re.S) if all(n in b for n in needles)]
    assert len(hits) == 1, (needles, len(hits)); return hits[0].replace('?', '%s')
def after(path, prefix, *needles):
    """A statement written as CONSTANT + " where ..." in Java."""
    hits = [m for m in re.findall(re.escape(prefix) + r' \+ "([^"]*)"', text(path)) if all(n in m for n in needles)]
    assert len(hits) == 1, (needles, hits); return hits[0].replace('?', '%s')

RS, AS, CS, ES, QS = 'approvals/ReviewStore.java', 'syllabus/AnalysisStore.java', 'approvals/CommentService.java', 'notes/ExportService.java', 'notes/QuizService.java'
RES_COLS = re.search(r'RESOURCE_COLUMNS = "([^"]*)"', text(RS)).group(1)
FIND_RES = RES_COLS + after(RS, 'RESOURCE_COLUMNS', 'coalesce(topic_id')
LIST_RES = RES_COLS + after(RS, 'RESOURCE_COLUMNS', 'order by title, level')
INS_RES = strs(RS, 'insert into resources'); LINK = strs(RS, 'insert into resource_links')
INS_VER = block(RS, 'insert into resource_versions'); MOVE = strs(RS, 'update resource_versions set status = ?')
SAVE = block(RS, 'update resource_versions set content')
DEPENDENTS = const(RS, 'DEPENDENTS_SQL'); OUTDATE = const(RS, 'OUTDATE_SQL')
def joined(path, *needles):
    """A statement split over several "..." + "..." literals."""
    runs = [''.join(re.findall(r'"([^"]*)"', m)) for m in re.findall(r'"[^"]*"(?:\s*\+\s*"[^"]*")+', text(path))]
    hits = [r for r in runs if all(n in r for n in needles)]
    assert len(hits) == 1, (needles, hits); return hits[0].replace('?', '%s')
APPROVED_OF_KIND = joined(RS, "v.status = 'APPROVED'", 'r.kind = ?')
VCOLS = const(RS, 'VERSION_COLUMNS')
NEWEST = re.search(r'"select distinct on \(v\.resource_id\) " \+ VERSION_COLUMNS \+ (.*?)ReviewStore::version', text(RS), re.S).group(1)
NEWEST = 'select distinct on (v.resource_id) ' + VCOLS + ''.join(re.findall(r'"([^"]*)"', NEWEST)).replace('?', '%s')
INS_JOB = strs(AS, 'insert into analysis_jobs'); JOB_SQL = const(AS, 'JOB_SQL')
RECENT = 'select distinct on (j.target) ' + JOB_SQL.strip()[len('select '):] + re.search(r'substring\("select "\.length\(\)\)\s*\+ "([^"]*)"', text(AS)).group(1).replace('?', '%s')
CLAIM = const(AS, 'CLAIM_SQL')
LIST_C = block(CS, 'from review_comments c'); ADD_C = strs(CS, 'insert into review_comments')
RESOLVE_C = strs(CS, 'set resolved_at = now()'); REOPEN_C = strs(CS, 'resolved_at = null'); DEL_C = strs(CS, 'delete from review_comments')
INS_FIG = strs(ES, 'insert into note_figures'); GET_FIG = strs(ES, 'select svg from note_figures')
INS_QUIZ = strs(QS, 'insert into quizzes'); LIST_Q = block(QS, 'jsonb_array_length'); GET_Q = block(QS, 'q.config::text')
DEL_Q = strs(QS, 'delete from quizzes')

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
status = lambda v: sysc.execute("select status from resource_versions where id=%s", (v,)).fetchone()[0]

sysc = conn()
admin = sysc.execute("select id from users where role='ADMIN' and status='ACTIVE' limit 1").fetchone()[0]
sysc.execute("update users set status='DEACTIVATED' where id <> %s and status <> 'DEACTIVATED'", (admin,))
tag = uuid.uuid4().hex[:6]
mk = lambda n: sysc.execute("insert into users(email,display_name,password_hash) values (%s,%s,'x') returning id", (f"{n}-{tag}@t.lk", n)).fetchone()[0]
lect, rev, stu, out = mk('Lecturer'), mk('Reviewer'), mk('Student'), mk('Outsider')
cl, cr, cs, co = conn('user', lect), conn('user', rev), conn('user', stu), conn('user', out)
proj = uuid.uuid4()
cl.execute("insert into projects(id,name,kind) values (%s,%s,'EDUCATION')", (proj, 'DBMS '+tag))
cl.execute("insert into project_members(project_id,user_id,role,added_by) values (%s,%s,'REVIEWER',%s),(%s,%s,'VIEWER',%s)", (proj, rev, lect, proj, stu, lect))

# an approved topic list to build on
top = uuid.uuid4(); cl.execute(INS_RES, (top, proj, 'TOPICS', 'Topics', None, None))
tv1 = uuid.uuid4(); cl.execute(INS_VER, (tv1, top, 1, 'DRAFT', 'MANUAL', '{"nodes":[]}', 'a'*64, None, None))
cl.execute(MOVE, ('IN_REVIEW', tv1, 'DRAFT')); cr.execute(MOVE, ('APPROVED', tv1, 'IN_REVIEW'))

# notes: one resource per topic and level, written by the worker as the lecturer
w = conn('worker', lect)
job = uuid.uuid4(); cl.execute(INS_JOB, (job, proj, 'NOTES', 't1:BEGINNER', '{}'))
check('a second job for the same notes is refused', fails(cl, INS_JOB, uuid.uuid4(), proj, 'NOTES', 't1:BEGINNER', '{}') == '23505')
job2 = uuid.uuid4()
check('another level can be written at the same time', fails(cl, INS_JOB, job2, proj, 'NOTES', 't1:EXAM', '{}') is None)
check('a viewer cannot start one', fails(cs, INS_JOB, uuid.uuid4(), proj, 'NOTES', 't2:EXAM', '{}') == '42501')
claimed = [r[0] for r in sysc.execute(CLAIM, (10,)).fetchall()]
check('the worker claims both', job in claimed and job2 in claimed)
recent = cs.execute(RECENT, (proj, 'NOTES')).fetchall()
check('recent jobs: one row per target, visible to viewers', sorted(r[11] for r in recent) == ['t1:BEGINNER', 't1:EXAM'], recent)

n1 = uuid.uuid4(); w.execute(INS_RES, (n1, proj, 'NOTES', 'Normalisation: Beginner', 't1', 'BEGINNER'))
check('the same topic and level twice is refused', fails(cl, INS_RES, uuid.uuid4(), proj, 'NOTES', 'dup', 't1', 'BEGINNER') == '23505')
check('notes need a level', fails(cl, INS_RES, uuid.uuid4(), proj, 'NOTES', 'x', 't1', None) is not None)
check('a bank has no level', fails(cl, INS_RES, uuid.uuid4(), proj, 'MCQ', 'x', 't1', 'BEGINNER') is not None)
check('a syllabus has no topic', fails(cl, INS_RES, uuid.uuid4(), proj, 'SYLLABUS', 'x', 't1', None) is not None)
w.execute(LINK, (n1, top))
nv1 = uuid.uuid4(); w.execute(INS_VER, (nv1, n1, 1, 'DRAFT', 'AI', '{"doc":{"type":"doc"}}', 'b'*64, None, tv1))
row = cs.execute(FIND_RES, (proj, 'NOTES', 't1', 'BEGINNER')).fetchone()
check('found by topic and level', row and row[0] == n1 and row[4] == 't1' and row[5] == 'BEGINNER', row)
check('not found at another level', cs.execute(FIND_RES, (proj, 'NOTES', 't1', 'EXAM')).fetchone() is None)
check('the topic list is still found without topic or level', cs.execute(FIND_RES, (proj, 'TOPICS', '', '')).fetchone()[0] == top)
check('an outsider sees no notes', co.execute(LIST_RES, (proj, 'NOTES')).fetchall() == [])
check('the AI cannot approve its notes', fails(w, MOVE, 'APPROVED', nv1, 'DRAFT') == 'LX011')
cl.execute(MOVE, ('IN_REVIEW', nv1, 'DRAFT'))

# comments while in review
c1 = uuid.uuid4()
check('the reviewer comments on a passage', fails(cr, ADD_C, c1, proj, n1, nv1, 'update anomalies', 'Add an example') is None)
check('a viewer cannot comment', fails(cs, ADD_C, uuid.uuid4(), proj, n1, nv1, None, 'Nice') == '42501')
check('an outsider cannot comment', fails(co, ADD_C, uuid.uuid4(), proj, n1, nv1, None, 'Hi') == '42501')
check('a viewer can read the comments', [r[4] for r in cs.execute(LIST_C, (n1,)).fetchall()] == ['Add an example'])
check('nobody can rewrite a comment', fails(cr, "update review_comments set body='changed' where id=%s", c1) == 'LX013')
check('the lecturer resolves it', cl.execute(RESOLVE_C, (lect, c1)).rowcount == 1)
check('resolving twice does nothing', cl.execute(RESOLVE_C, (lect, c1)).rowcount == 0)
check('a viewer cannot reopen it', cs.execute(REOPEN_C, (c1,)).rowcount == 0)
check('the reviewer cannot delete a resolved comment', cr.execute(DEL_C, (c1,)).rowcount == 0)
cr.execute(REOPEN_C, (c1,))
check('the lecturer cannot delete the reviewer’s comment', cl.execute(DEL_C, (c1,)).rowcount == 0)
check('the reviewer deletes their own open comment', cr.execute(DEL_C, (c1,)).rowcount == 1)

cr.execute(MOVE, ('APPROVED', nv1, 'IN_REVIEW'))
check('approved notes are listed by kind', cs.execute(APPROVED_OF_KIND, (proj, 'NOTES')).fetchall() == [(nv1,)])

# a question bank built on the approved notes
b1 = uuid.uuid4(); cl.execute(INS_RES, (b1, proj, 'MCQ', 'Normalisation: questions', 't1', None)); cl.execute(LINK, (b1, n1))
bank = {"topicId": "t1", "questions": [{"id": "a", "stem": "Which?", "options": ["1NF", "2NF", "3NF", "BCNF"], "answer": 2}]}
bv1 = uuid.uuid4(); cl.execute(INS_VER, (bv1, b1, 1, 'DRAFT', 'MANUAL', json.dumps(bank), 'c'*64, None, nv1))
bank['questions'].append({"id": "b", "stem": "Why?", "options": ["w", "x", "y", "z"], "answer": 0})
check('the lecturer edits the draft bank', cl.execute(SAVE, (json.dumps(bank), 'd'*64, nv1, bv1)).rowcount == 1)
cl.execute(MOVE, ('IN_REVIEW', bv1, 'DRAFT')); cr.execute(MOVE, ('APPROVED', bv1, 'IN_REVIEW'))
newest = cs.execute(NEWEST, (proj, 'MCQ')).fetchall()
check('newest bank version comes with its content', len(newest) == 1 and len(json.loads(newest[0][-1])['questions']) == 2, newest)
check('the bank depends on the notes, which depend on the topics', set(r[0] for r in cs.execute(DEPENDENTS, (top,)).fetchall()) == {n1, b1})

# figures drawn in the browser are kept per project
svg = '<svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0"/></svg>'
check('the reviewer keeps a figure', fails(cr, INS_FIG, proj, 'MATH', 'e'*64, svg) is None)
check('keeping it again is harmless', fails(cl, INS_FIG, proj, 'MATH', 'e'*64, '<svg/>') is None)
check('a viewer cannot add figures', fails(cs, INS_FIG, proj, 'MERMAID', 'f'*64, svg) == '42501')
check('the first drawing is kept', cs.execute(GET_FIG, (proj, 'MATH', 'e'*64)).fetchone()[0] == svg)
check('an outsider cannot read it', co.execute(GET_FIG, (proj, 'MATH', 'e'*64)).fetchone() is None)
check('figures cannot be redrawn in place', cl.execute("update note_figures set svg='<svg/>' where project_id=%s", (proj,)).rowcount == 0)

# quizzes: a snapshot
qz = uuid.uuid4(); qs = [{"number": 1, "bankId": str(b1), "stem": "Which?", "options": ["3NF", "1NF", "BCNF", "2NF"], "answer": 0}]
check('the lecturer saves a quiz', fails(cl, INS_QUIZ, qz, proj, 'Week 3', 42, '{"count":1}', json.dumps(qs)) is None)
check('a reviewer cannot make quizzes', fails(cr, INS_QUIZ, uuid.uuid4(), proj, 'x', 1, '{}', '[]') == '42501')
lst = cs.execute(LIST_Q, (proj,)).fetchall()
check('a viewer lists it with its size, seed and maker', lst and lst[0][1:5] == ('Week 3', 1, 42, 'Lecturer'), lst)
check('the snapshot comes back whole', json.loads(cs.execute(GET_Q, (qz,)).fetchone()[4]) == qs)
check('a quiz cannot be changed', cl.execute("update quizzes set title='x' where id=%s", (qz,)).rowcount == 0)
check('an outsider cannot see it', co.execute(GET_Q, (qz,)).fetchone() is None)
check('a viewer cannot delete it', cs.execute(DEL_Q, (qz,)).rowcount == 0)

# changing the approved topics outdates the notes and then the bank built on them
tv2 = uuid.uuid4(); cl.execute(INS_VER, (tv2, top, 2, 'DRAFT', 'EDIT', '{"nodes":[1]}', 'g'*64, tv1, None))
changed = []
for r in [r[0] for r in cl.execute(DEPENDENTS, (top,)).fetchall()]:
    changed += cl.execute(OUTDATE, (r,)).fetchall()
check('notes and bank become Outdated, with their old status', sorted(changed) == sorted([(nv1, 'APPROVED'), (bv1, 'APPROVED')]), changed)
check('nothing approved remains for quizzes', cs.execute(APPROVED_OF_KIND, (proj, 'MCQ')).fetchall() == [])
check('existing quizzes are untouched', json.loads(cs.execute(GET_Q, (qz,)).fetchone()[4]) == qs)
check('outdated notes are frozen', cl.execute(SAVE, ('{}', 'h'*64, tv1, nv1)).rowcount == 0)
check('the lecturer deletes the quiz', cl.execute(DEL_Q, (qz,)).rowcount == 1)

sysc.execute("delete from projects where id=%s", (proj,))
sysc.execute("set session_replication_role = replica")
for u in (lect, rev, stu, out): sysc.execute("delete from users where id=%s", (u,))
print(f"E4 end to end: {ok} checks passed")
