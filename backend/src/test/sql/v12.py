"""Real PostgreSQL checks for V12: notes and MCQ resources, comments, figures, quizzes."""
import _env
import psycopg, uuid, sys, re
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
co, cr, cv, cx = conn('user', own), conn('user', rev), conn('user', view), conn('user', out)
proj = uuid.uuid4()
q(co, "insert into projects(id,name,kind) values (%s,%s,'EDUCATION')", proj, 'DBMS '+tag)
q(co, "insert into project_members(project_id,user_id,role,added_by) values (%s,%s,'REVIEWER',%s),(%s,%s,'VIEWER',%s)", proj, rev, own, proj, view, own)
R = "insert into resources(id,project_id,kind,title,topic_id,level) values (%s,%s,%s,%s,%s,%s)"
n1, n2, m1 = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
check('notes per topic and level', fails(co, R, n1, proj, 'NOTES', 'Keys', 'a1', 'BEGINNER') is None and fails(co, R, n2, proj, 'NOTES', 'Keys', 'a1', 'EXAM') is None)
check('same topic and level twice refused', fails(co, R, uuid.uuid4(), proj, 'NOTES', 'Keys', 'a1', 'BEGINNER') == '23505')
check('notes need a topic and level', fails(co, R, uuid.uuid4(), proj, 'NOTES', 'x', None, None) == '23514')
check('a bank per topic, without a level', fails(co, R, m1, proj, 'MCQ', 'Keys', 'a1', None) is None)
check('a bank with a level refused', fails(co, R, uuid.uuid4(), proj, 'MCQ', 'x', 'a2', 'EXAM') == '23514')
check('one syllabus still', fails(co, R, uuid.uuid4(), proj, 'SYLLABUS', 'S', None, None) is None and fails(co, R, uuid.uuid4(), proj, 'SYLLABUS', 'S', None, None) == '23505')
check('unknown level refused', fails(co, R, uuid.uuid4(), proj, 'NOTES', 'x', 'a3', 'EXPERT') == '23514')

v = uuid.uuid4()
q(co, "insert into resource_versions(id,resource_id,version_no,status,origin,content,content_hash) values (%s,%s,1,'IN_REVIEW','AI','{}',%s)", v, n1, 'a'*64)
C = "insert into review_comments(id,project_id,resource_id,version_id,quote,body) values (%s,%s,%s,%s,%s,%s)"
c1 = uuid.uuid4()
check('reviewer comments on a version in review', fails(cr, C, c1, proj, n1, v, '3NF', 'Explain transitive dependency') is None)
check('viewer cannot comment', fails(cv, C, uuid.uuid4(), proj, n1, v, None, 'hi') == '42501')
check('outsider cannot comment', fails(cx, C, uuid.uuid4(), proj, n1, v, None, 'hi') == '42501')
check('empty comment refused', fails(cr, C, uuid.uuid4(), proj, n1, v, None, '   ') == '23514')
check('cannot comment as someone else', fails(cr, "insert into review_comments(id,project_id,resource_id,version_id,body,author_id) values (%s,%s,%s,%s,'x',%s)", uuid.uuid4(), proj, n1, v, own) == '42501')
check('owner resolves it', len(q(co, "update review_comments set resolved_at=now(), resolved_by=%s where id=%s returning id", own, c1)) == 1)
check('nobody rewrites a comment', fails(co, "update review_comments set body='changed' where id=%s", c1) == 'LX013')
check('resolved comments are not deleted', q(cr, "delete from review_comments where id=%s returning id", c1) == [])
check('viewer reads comments', q(cv, "select count(*) from review_comments where resource_id=%s", n1)[0][0] == 1)
check('outsider reads none', q(cx, "select count(*) from review_comments where resource_id=%s", n1)[0][0] == 0)
check('the version itself is untouched by comments', q(sysc, "select status from resource_versions where id=%s", v)[0][0] == 'IN_REVIEW')

F = "insert into note_figures(project_id,kind,source_hash,svg) values (%s,%s,%s,%s)"
check('editor stores a drawing', fails(co, F, proj, 'MATH', 'b'*64, '<svg/>') is None)
check('reviewer stores a drawing', fails(cr, F, proj, 'MERMAID', 'c'*64, '<svg/>') is None)
check('viewer cannot', fails(cv, F, proj, 'MATH', 'd'*64, '<svg/>') == '42501')
check('drawings are never replaced', fails(co, F, proj, 'MATH', 'b'*64, '<svg>other</svg>') == '23505' and q(co, "update note_figures set svg='x' returning 1") == [])
check('huge drawings refused', fails(co, F, proj, 'MATH', 'e'*64, 'x'*600001) == '23514')
check('outsider sees no drawings', q(cx, "select count(*) from note_figures where project_id=%s", proj)[0][0] == 0)

Z = "insert into quizzes(id,project_id,title,seed,config,questions) values (%s,%s,'Quiz',42,'{}','[]')"
z = uuid.uuid4()
check('editor makes a quiz', fails(co, Z, z, proj) is None)
check('viewer cannot', fails(cv, Z, uuid.uuid4(), proj) == '42501')
check('viewer reads it', q(cv, "select seed from quizzes where id=%s", z) == [(42,)])
check('quizzes cannot be edited', q(co, "update quizzes set seed=1 where id=%s returning id", z) == [])

J = "insert into analysis_jobs(id,project_id,kind,input,target) values (%s,%s,%s,'{}',%s)"
check('notes for two topics at once', fails(co, J, uuid.uuid4(), proj, 'NOTES', 'a1:BEGINNER') is None and fails(co, J, uuid.uuid4(), proj, 'NOTES', 'a2:BEGINNER') is None)
check('the same notes twice at once refused', fails(co, J, uuid.uuid4(), proj, 'NOTES', 'a1:BEGINNER') == '23505')
check('a bank job beside notes jobs', fails(co, J, uuid.uuid4(), proj, 'MCQ', 'a1') is None)

q(sysc, "delete from projects where id=%s", proj)
check('project deletion removes all of it', q(sysc, "select (select count(*) from review_comments where project_id=%s)+(select count(*) from note_figures where project_id=%s)+(select count(*) from quizzes where project_id=%s)", proj, proj, proj)[0][0] == 0)
q(sysc, "set session_replication_role = replica")
for u in (own, rev, view, out): q(sysc, "delete from users where id=%s", u)
print(f"V12: {ok} checks passed")
