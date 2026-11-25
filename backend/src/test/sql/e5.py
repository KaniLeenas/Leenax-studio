"""E5 end to end on real PostgreSQL with the exact SQL in QaStore.java and ReviewStore.java.
The specification's check: a planted wrong answer key blocks approval."""
import _env
import psycopg, uuid, sys, re, json
DSN = _env.DSN
SRC = _env.SRC
ACT_SQL = re.search(r'ACT_SQL = """(.*?)""";', open(SRC+'access/ActorDataSource.java').read(), re.S).group(1).replace('?', '%s')
def text(path): return open(SRC+path).read()
def const(path, name): return re.search(name + r' = """(.*?)""";', text(path), re.S).group(1).replace('?', '%s')
def strs(path, *needles):
    hits = [m for m in re.findall(r'"((?:select|insert|update|delete)[^"]*)"', text(path)) if all(n in m for n in needles)]
    assert len(hits) == 1, (needles, hits); return hits[0].replace('?', '%s')
def block(path, *needles):
    hits = [b for b in re.findall(r'"""(.*?)"""', text(path), re.S) if all(n in b for n in needles)]
    assert len(hits) == 1, (needles, len(hits)); return hits[0].replace('?', '%s')
def joined(path, *needles):
    runs = [''.join(re.findall(r'"([^"]*)"', m)) for m in re.findall(r'"[^"]*"(?:\s*\+\s*"[^"]*")+', text(path))]
    hits = [r for r in runs if all(n in r for n in needles)]
    assert len(hits) == 1, (needles, hits); return hits[0].replace('?', '%s')
def after(path, const_name, *needles):
    hits = [m for m in re.findall(re.escape(const_name) + r' \+ "([^"]*)"', text(path)) if all(n in m for n in needles)]
    assert len(hits) == 1, (needles, hits); return const(path, const_name) + hits[0].replace('?', '%s')

QS, RS = 'qa/QaStore.java', 'approvals/ReviewStore.java'
INS_RUN = joined(QS, 'insert into qa_runs'); INS_ISSUE = joined(QS, 'insert into qa_issues')
LATEST_CHECK = after(QS, 'RUN_SQL', "r.mode = 'CHECK' order by r.created_at desc limit 1", 'r.resource_id = ? and r.mode')
OF_CONTENT = after(QS, 'RUN_SQL', 'r.content_hash = ?')
RUNS = after(QS, 'RUN_SQL', 'r.resource_id = ? order by r.created_at desc')
ISSUES = after(QS, 'ISSUE_SQL', 'order by i.number'); ISSUE = after(QS, 'ISSUE_SQL', 'i.id = ?')
NEXT = strs(QS, 'max(number)'); CORRECTED = strs(QS, "status = 'CORRECTED'")
FIXED = strs(QS, "status = 'RESOLVED'"); NOT_FIXED = strs(QS, "set status = 'OPEN', recheck")
DISMISS = strs(QS, "status = 'DISMISSED'"); REOPEN = strs(QS, "set status = 'OPEN', status_note")
CONFIRM = strs(QS, 'set confirmations'); INS_CORR = strs(QS, 'insert into qa_corrections')
CORR = after(QS, 'CORRECTION_SQL', 'c.id = ?'); DECIDE = strs(QS, 'update qa_corrections set status')
OPEN = const(QS, 'OPEN_SQL')
INS_RES = strs(RS, 'insert into resources'); LINK = strs(RS, 'insert into resource_links'); INS_VER = block(RS, 'insert into resource_versions')
MOVE = strs(RS, 'update resource_versions set status = ?'); SAVE = block(RS, 'update resource_versions set content')

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
lect, rev, stu, out = mk('Lecturer'), mk('Reviewer'), mk('Student'), mk('Outsider')
cl, cr, cs, co = conn('user', lect), conn('user', rev), conn('user', stu), conn('user', out)
proj = uuid.uuid4()
cl.execute("insert into projects(id,name,kind) values (%s,%s,'EDUCATION')", (proj, 'Maths '+tag))
cl.execute("insert into project_members(project_id,user_id,role,added_by) values (%s,%s,'REVIEWER',%s),(%s,%s,'VIEWER',%s)", (proj, rev, lect, proj, stu, lect))

# a bank whose first key is planted wrong (2 + 2 = 5)
bank = uuid.uuid4(); cl.execute(INS_RES, (bank, proj, 'MCQ', 'Addition', 't1', None))
content = {"topicId": "t1", "questions": [{"id": "q1", "stem": "2 + 2 = ?", "options": ["3", "4", "5", "6"], "answer": 2}]}
v1 = uuid.uuid4(); cl.execute(INS_VER, (v1, bank, 1, 'DRAFT', 'AI', json.dumps(content), 'a'*64, None, None))

# the reviewer runs QA; the worker records it as them, with the exact SQL
w = conn('worker', rev)
run = uuid.uuid4()
check('the run is recorded', fails(w, INS_RUN, run, proj, bank, v1, 'a'*64, None, 'CHECK', 'claude-y', 'Anthropic', 'gemini-z', 'gpt-x', True,
                                   json.dumps({"categories": {"MCQ_KEYS": {"checked": 1}}})) is None)
n = w.execute(NEXT, (bank,)).fetchone()[0]
check('issues are numbered from 1', n == 1)
key = uuid.uuid4()
ev = json.dumps([{"basis": "AI"}]); conf = json.dumps([{"model": "gemini-z", "agrees": True, "note": "Its answer: B"}])
check('the wrong key is recorded', fails(w, INS_ISSUE, key, proj, bank, run, v1, n, 'q1', 'Question 1', 'MCQ_KEYS', 'WRONG_KEY', 'CRITICAL',
                                         '2 + 2 = ?\nKey: C) 5', "The key is C, but the checker's answer is B", 'AI', ev, 'Mark B', conf) is None)
corr = uuid.uuid4()
check('with its proposed correction', fails(w, INS_CORR, corr, proj, key, json.dumps({"questionId": "q1", "answer": 1})) is None)
check('the checker can\'t call it minor', fails(w, INS_ISSUE, uuid.uuid4(), proj, bank, run, v1, 2, 'q1', 'Q1', 'MCQ_KEYS', 'WRONG_KEY', 'MINOR',
                                                None, 'x', 'AI', '[]', None, '[]') == '23514')
check('the newest check is found', w.execute(LATEST_CHECK, (bank,)).fetchone()[0] == run)
check('and matched to this content', cs.execute(OF_CONTENT, (bank, 'a'*64)).fetchone()[0] == run)
row = cs.execute(ISSUE, (key,)).fetchone()
check('a viewer reads the issue with its version number', row[1] == 1 and row[5] == 1 and row[10] == 'CRITICAL' and json.loads(row[16])[0]['agrees'])
check('an outsider sees nothing', co.execute(ISSUES, (bank,)).fetchall() == [] and co.execute(RUNS, (bank,)).fetchall() == [])
check('the gate counts one open critical issue', dict((r[0], r[1]) for r in cs.execute(OPEN, (bank, False, bank)).fetchall()) == {'CRITICAL': 1})

# the planted key blocks approval
cl.execute(MOVE, ('IN_REVIEW', v1, 'DRAFT'))
check('the reviewer cannot approve it', fails(cr, MOVE, 'APPROVED', v1, 'IN_REVIEW') == 'LX014')
check('nor approve it with edits', fails(cr, INS_VER, uuid.uuid4(), bank, 2, 'APPROVED', 'REVIEWER', json.dumps(content), 'b'*64, v1, None) == 'LX014')

# the lecturer withdraws, accepts the correction into the draft, and it awaits a recheck
cl.execute(MOVE, ('DRAFT', v1, 'IN_REVIEW'))
check('a viewer cannot accept the correction', cs.execute(DECIDE, ('ACCEPTED', v1, None, corr)).rowcount == 0)
check('the reviewer cannot accept it (it changes content)', fails(cr, DECIDE, 'ACCEPTED', v1, None, corr) == 'LX011')
content['questions'][0]['answer'] = 1
check('the lecturer saves the corrected draft', cl.execute(SAVE, (json.dumps(content), 'c'*64, None, v1)).rowcount == 1)
check('and accepts the correction', cl.execute(DECIDE, ('ACCEPTED', v1, 'Confirmed: 4', corr)).rowcount == 1)
check('a decided correction stays decided', cl.execute(DECIDE, ('REJECTED', None, None, corr)).rowcount == 0)
check('the issue is corrected', cl.execute(CORRECTED, ('Correction accepted in version 1; recheck to close it', key)).rowcount == 1)
c = cs.execute(CORR, (corr,)).fetchone()
check('the correction names who decided', c[3] == 'ACCEPTED' and c[6] == 'Lecturer')
check('the old check doesn\'t cover the corrected content', cs.execute(OF_CONTENT, (bank, 'c'*64)).fetchone() is None)
cl.execute(MOVE, ('IN_REVIEW', v1, 'DRAFT'))
check('corrected but not rechecked still blocks', fails(cr, MOVE, 'APPROVED', v1, 'IN_REVIEW') == 'LX014')

# a recheck (the worker) resolves it; a second model's confirmations can be added
cl.execute(MOVE, ('DRAFT', v1, 'IN_REVIEW'))
check('a failed recheck reopens it', w.execute(NOT_FIXED, (json.dumps({"fixed": False}), key)).rowcount == 1 and
      sysc.execute("select status from qa_issues where id=%s", (key,)).fetchone()[0] == 'OPEN')
check('confirmations can be added', w.execute(CONFIRM, (conf, key)).rowcount == 1)
check('a passing recheck resolves it', w.execute(FIXED, (json.dumps({"fixed": True, "versionNo": 1}), key)).rowcount == 1)
check('resolving twice does nothing', w.execute(FIXED, (json.dumps({"fixed": True}), key)).rowcount == 0)
check('the gate is clear', cs.execute(OPEN, (bank, False, bank)).fetchall() == [])
cl.execute(MOVE, ('IN_REVIEW', v1, 'DRAFT'))
check('now it can be approved', fails(cr, MOVE, 'APPROVED', v1, 'IN_REVIEW') is None)

# dismissing and reopening (a reviewer, with a reason)
n2 = w.execute(NEXT, (bank,)).fetchone()[0]
minor = uuid.uuid4(); w.execute(INS_ISSUE, (minor, proj, bank, run, v1, n2, 'q1', 'Question 1', 'EXPLANATIONS', 'WEAK_EXPLANATION', 'MINOR',
                                            None, 'Too short', 'AI', '[]', None, '[]'))
check('a viewer cannot dismiss', cs.execute(DISMISS, ('no', minor)).rowcount == 0)
check('the reviewer dismisses with a reason', cr.execute(DISMISS, ('House style keeps it short', minor)).rowcount == 1)
check('and can reopen it', cr.execute(REOPEN, ('Needs a fuller explanation', minor)).rowcount == 1)
check('reopening an open issue does nothing', cr.execute(REOPEN, ('again', minor)).rowcount == 0)

# a topic's QA report is blocked by open critical issues on what it covers
rep = uuid.uuid4(); cl.execute(INS_RES, (rep, proj, 'QA', 'Addition: QA report', 't1', None)); cl.execute(LINK, (rep, bank))
crit2 = uuid.uuid4(); w.execute(INS_ISSUE, (crit2, proj, bank, run, v1, n2 + 1, 'q1', 'Question 1', 'MCQ_KEYS', 'AMBIGUOUS', 'CRITICAL',
                                            None, 'Two options can be defended', 'AI', '[]', None, '[]'))
check('the report gate sees the bank\'s issue', dict((r[0], r[1]) for r in cs.execute(OPEN, (rep, True, rep)).fetchall()).get('CRITICAL') == 1)
check('the bank gate ignores reports', cs.execute(OPEN, (bank, True, bank)).fetchall() != [])
rv = uuid.uuid4(); cl.execute(INS_VER, (rv, rep, 1, 'DRAFT', 'MANUAL', '{"topicId":"t1","items":[],"parts":{}}', 'd'*64, None, None))
cl.execute(MOVE, ('IN_REVIEW', rv, 'DRAFT'))
check('the report cannot be approved', fails(cr, MOVE, 'APPROVED', rv, 'IN_REVIEW') == 'LX014')
cr.execute(DISMISS, ('The lecturer confirmed one answer', crit2))
check('and can once the issue is dismissed', fails(cr, MOVE, 'APPROVED', rv, 'IN_REVIEW') is None)
check('the run history lists the check', [r[5] for r in cs.execute(RUNS, (bank,)).fetchall()] == ['CHECK'])

sysc.execute("delete from projects where id=%s", (proj,))
sysc.execute("set session_replication_role = replica")
for u in (lect, rev, stu, out): sysc.execute("delete from users where id=%s", (u,))
print(f"E5 end to end: {ok} checks passed")
