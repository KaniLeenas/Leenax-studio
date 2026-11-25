"""Real PostgreSQL checks for V13: QA runs, issues, corrections, and the blocking rule."""
import _env
import psycopg, uuid, sys, re, json
DSN = _env.DSN
ACT_SQL = re.search(r'ACT_SQL = """(.*?)""";', open(_env.SRC + 'access/ActorDataSource.java').read(), re.S).group(1).replace('?', '%s')
def conn(mode='system', uid=None):
    c = psycopg.connect(DSN, autocommit=True); u = str(uid) if uid else None
    c.execute(ACT_SQL, (mode, u if mode != 'user' else None, mode, mode, mode, u, None)); return c
ok = 0
def check(name, cond, got=None):
    global ok
    if not cond: sys.exit(f"FAIL {name}: {got}")
    ok += 1
def q(c, sql, *p):
    cur = c.execute(sql, p)
    try: return cur.fetchall()
    except psycopg.ProgrammingError: return None
def fails(c, sql, *p):
    try: q(c, sql, *p); return None
    except psycopg.Error as e: return e.sqlstate

sysc = conn()
admin = q(sysc, "select id from users where role='ADMIN' and status='ACTIVE' limit 1")[0][0]
q(sysc, "update users set status='DEACTIVATED' where id <> %s and status <> 'DEACTIVATED'", admin)
tag = uuid.uuid4().hex[:6]
mk = lambda n: q(sysc, "insert into users(email,display_name,password_hash) values (%s,%s,'x') returning id", f"{n}-{tag}@t.lk", n)[0][0]
own, rev, view, out = mk('Owner'), mk('Reviewer'), mk('Viewer'), mk('Outsider')
ed = own
co, cr, cv, cx = conn('user', own), conn('user', rev), conn('user', view), conn('user', out)
ce = co
proj = uuid.uuid4()
q(co, "insert into projects(id,name,kind) values (%s,%s,'EDUCATION')", proj, 'QA '+tag)
q(co, "insert into project_members(project_id,user_id,role,added_by) values (%s,%s,'REVIEWER',%s),(%s,%s,'VIEWER',%s)",
  proj, rev, own, proj, view, own)
R = "insert into resources(id,project_id,kind,title,topic_id,level) values (%s,%s,%s,%s,%s,%s)"
V = "insert into resource_versions(id,resource_id,version_no,status,origin,content,content_hash) values (%s,%s,%s,%s,'MANUAL',%s,%s)"
MOVE = "update resource_versions set status=%s where id=%s"

# a bank with one question whose key is planted wrong (2 + 2 = 5)
bank = uuid.uuid4(); q(ce, R, bank, proj, 'MCQ', 'Arithmetic', 't1', None)
bv1 = uuid.uuid4()
q(ce, V, bv1, bank, 1, 'DRAFT', json.dumps({"questions": [{"id": "q1", "stem": "2 + 2 = ?", "options": ["3", "4", "5", "6"], "answer": 2}]}), 'a'*64)
check('a QA report per topic', fails(ce, R, uuid.uuid4(), proj, 'QA', 'Arithmetic: QA', 't1', None) is None)
check('a QA report needs a topic', fails(ce, R, uuid.uuid4(), proj, 'QA', 'x', None, None) == '23514')

# the reviewer starts QA; the worker runs it as them
J = "insert into analysis_jobs(id,project_id,kind,target,input) values (%s,%s,%s,%s,'{}')"
check('a reviewer can start a QA job', fails(cr, J, uuid.uuid4(), proj, 'QA', str(bank)) is None)
check('but not a notes job', fails(cr, J, uuid.uuid4(), proj, 'NOTES', 't1:EXAM') == '42501')
check('a viewer can start neither', fails(cv, J, uuid.uuid4(), proj, 'QA', 'x') == '42501')
w = conn('worker', rev)
run = uuid.uuid4()
RUN = "insert into qa_runs(id,project_id,resource_id,version_id,content_hash,mode,model,independent,result) values (%s,%s,%s,%s,repeat('a',64),'CHECK','gpt-x',true,'{}')"
check('the worker records the run', fails(w, RUN, run, proj, bank, bv1) is None)
check('a viewer cannot record a run', fails(cv, RUN, uuid.uuid4(), proj, bank, bv1) == '42501')
check('runs are never changed', q(co, "update qa_runs set model='y' where id=%s returning id", run) == [])
I = """insert into qa_issues(id,project_id,resource_id,run_id,found_in_version_id,number,item_ref,category,type,severity,original,explanation,basis,suggestion)
       values (%s,%s,%s,%s,%s,%s,'q1',%s,%s,%s,'Key: C (5)','2 + 2 is 4, option B','AI',%s)"""
key = uuid.uuid4()
check('a wrong key is recorded as critical', fails(w, I, key, proj, bank, run, bv1, 1, 'MCQ_KEYS', 'WRONG_KEY', 'CRITICAL', 'Mark B') is None)
check('a wrong key can\'t be filed as minor', fails(w, I, uuid.uuid4(), proj, bank, run, bv1, 2, 'MCQ_KEYS', 'WRONG_KEY', 'MINOR', None) == '23514')
check('only the five kinds can be critical', fails(w, I, uuid.uuid4(), proj, bank, run, bv1, 2, 'EXPLANATIONS', 'GRAMMAR', 'CRITICAL', None) == '23514')
minor = uuid.uuid4(); q(w, I, minor, proj, bank, run, bv1, 2, 'EXPLANATIONS', 'WEAK_EXPLANATION', 'MINOR', None)
check('numbers are unique per resource', fails(w, I, uuid.uuid4(), proj, bank, run, bv1, 2, 'EXPLANATIONS', 'GRAMMAR', 'MINOR', None) == '23505')
check('a viewer sees the issues', len(q(cv, "select id from qa_issues where resource_id=%s", bank)) == 2)
check('an outsider sees none', q(cx, "select id from qa_issues where resource_id=%s", bank) == [])

# the planted wrong key blocks approval
q(ce, MOVE, 'IN_REVIEW', bv1)
check('the reviewer cannot approve with a critical issue open', fails(cr, MOVE, 'APPROVED', bv1) == 'LX014')
check('the finding cannot be rewritten', fails(ce, "update qa_issues set explanation='fine' where id=%s", key) == 'LX015')
check('nor quietly downgraded', fails(cr, "update qa_issues set severity='MINOR', type='GRAMMAR' where id=%s", key) == 'LX015')
check('only a recheck resolves it', fails(ce, "update qa_issues set status='RESOLVED' where id=%s", key) == 'LX015')
check('a viewer cannot dismiss it', q(cv, "update qa_issues set status='DISMISSED', status_note='no' where id=%s returning id", key) == [])
check('the worker cannot dismiss it', fails(w, "update qa_issues set status='DISMISSED', status_note='no' where id=%s", key) == 'LX011')
check('dismissing needs a reason', fails(cr, "update qa_issues set status='DISMISSED' where id=%s", key) == '23514')

# a correction: proposed by the run, accepted by the editor
C = "insert into qa_corrections(id,project_id,issue_id,proposal) values (%s,%s,%s,%s)"
corr = uuid.uuid4(); check('the run proposes a correction', fails(w, C, corr, proj, key, json.dumps({"questionId": "q1", "answer": 1})) is None)
check('the reviewer cannot accept it', fails(cr, "update qa_corrections set status='ACCEPTED' where id=%s", corr) == 'LX011')
check('the worker cannot accept it', fails(w, "update qa_corrections set status='ACCEPTED' where id=%s", corr) == 'LX011')
check('the proposal cannot be edited', fails(ce, "update qa_corrections set proposal='{}' where id=%s", corr) == 'LX015')
q(ce, MOVE, 'DRAFT', bv1)
check('the editor accepts it', fails(ce, "update qa_corrections set status='ACCEPTED', note='ok' where id=%s", corr) is None)
check('it records who decided', q(co, "select decided_by is not null from qa_corrections where id=%s", corr) == [(True,)])
check('it can\'t be decided twice', fails(ce, "update qa_corrections set status='REJECTED' where id=%s", corr) == 'LX015')
check('the issue becomes corrected', fails(ce, "update qa_issues set status='CORRECTED' where id=%s", key) is None)
q(ce, MOVE, 'IN_REVIEW', bv1)
check('corrected but not rechecked still blocks', fails(cr, MOVE, 'APPROVED', bv1) == 'LX014')
check('a failed recheck reopens it', fails(w, "update qa_issues set status='OPEN', recheck='{\"fixed\":false}' where id=%s", key) is None)
check('a passing recheck resolves it', fails(w, "update qa_issues set status='RESOLVED', recheck='{\"fixed\":true}' where id=%s", key) is None)
check('now the reviewer can approve', fails(cr, MOVE, 'APPROVED', bv1) is None)
check('minor issues never block', q(sysc, "select status from qa_issues where id=%s", minor) == [('OPEN',)])

# reopening, and dismissing as the author
check('a reviewer can reopen a resolved issue', fails(cr, "update qa_issues set status='OPEN', status_note='still wrong in v2' where id=%s", key) is None)
q(co, "update projects set reviewer_not_author=true where id=%s", proj)
q(sysc, "set session_replication_role = replica"); q(sysc, "update resource_versions set created_by=%s where id=%s", rev, bv1); q(sysc, "set session_replication_role = origin")  # the reviewer wrote it
check('the author cannot dismiss a critical issue on their work', fails(cr, "update qa_issues set status='DISMISSED', status_note='fine' where id=%s", key) == 'LX012')
check('the owner can, with a reason', fails(co, "update qa_issues set status='DISMISSED', status_note='The key was confirmed by the lecturer' where id=%s", key) is None)

# a QA report is blocked by open critical issues on what it covers
rep = q(sysc, "select id from resources where project_id=%s and kind='QA'", proj)[0][0]
q(ce, "insert into resource_links(resource_id, depends_on_id) values (%s,%s)", rep, bank)
q(co, "update qa_issues set status='OPEN' where id=%s", key)
rv = uuid.uuid4(); q(ce, V, rv, rep, 1, 'DRAFT', '{}', 'b'*64); q(ce, MOVE, 'IN_REVIEW', rv)
check('the report is blocked while the bank has an open critical issue', fails(cr, MOVE, 'APPROVED', rv) == 'LX014')
q(co, "update qa_issues set status='DISMISSED', status_note='Confirmed' where id=%s", key)
check('and approvable once it is dealt with', fails(cr, MOVE, 'APPROVED', rv) is None)

q(sysc, "delete from projects where id=%s", proj)
q(sysc, "set session_replication_role = replica")
for u in (own, rev, view, out): q(sysc, "delete from users where id=%s", u)
print(f"V13: {ok} checks passed")
