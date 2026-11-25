"""Real PostgreSQL checks for V11: versions, decisions and who may make them."""
import _env
import psycopg, uuid, sys, re, json
DSN = _env.DSN
_java = open(_env.SRC + 'access/ActorDataSource.java').read()
ACT_SQL = re.search(r'ACT_SQL = """(.*?)""";', _java, re.S).group(1).replace('?', '%s')
def conn(mode='system', uid=None):
    c = psycopg.connect(DSN, autocommit=True)
    u = str(uid) if uid else None
    c.execute(ACT_SQL, (mode, u if mode != 'user' else None, mode, mode, mode, u, None))
    return c
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
def mk(n): return q(sysc, "insert into users(email,display_name,password_hash) values (%s,%s,'x') returning id", f"{n}-{tag}@t.lk", n)[0][0]
own, ed, rev, out = mk('Owner'), mk('Editor'), mk('Reviewer'), mk('Outsider')
co, ce, cr, cx, ca = conn('user', own), conn('user', ed), conn('user', rev), conn('user', out), conn('user', admin)
proj = uuid.uuid4()
q(co, "insert into projects(id,name,kind) values (%s,%s,'EDUCATION')", proj, 'DBMS '+tag)
q(co, "insert into project_members(project_id,user_id,role,added_by) values (%s,%s,'EDITOR',%s),(%s,%s,'REVIEWER',%s)", proj, ed, own, proj, rev, own)

res = uuid.uuid4()
q(ce, "insert into resources(id,project_id,kind,title) values (%s,%s,'SYLLABUS','Syllabus')", res, proj)
check('second syllabus in a project refused', fails(co, "insert into resources(id,project_id,kind,title) values (%s,%s,'SYLLABUS','x')", uuid.uuid4(), proj) == '23505')
check('reviewer cannot create a resource', fails(cr, "insert into resources(id,project_id,kind,title) values (%s,%s,'TOPICS','x')", uuid.uuid4(), proj) == '42501')
V = "insert into resource_versions(id,resource_id,version_no,status,origin,content,content_hash,based_on_version_id) values (%s,%s,%s,%s,%s,%s::jsonb,%s,%s)"
v1 = uuid.uuid4()
q(ce, V, v1, res, 1, 'DRAFT', 'MANUAL', '{"a":1}', 'h'*64, None)
check('draft content can change', len(q(ce, "update resource_versions set content='{\"a\":2}' where id=%s returning id", v1)) == 1)
q(ce, "update resource_versions set status='IN_REVIEW' where id=%s", v1)
check('content frozen once in review', fails(ce, "update resource_versions set content='{\"a\":3}' where id=%s", v1) == 'LX010')
check('author cannot be changed', fails(sysc, "update resource_versions set created_by=%s where id=%s", out, v1) == 'LX010')
check('editor cannot decide', fails(ce, "update resource_versions set status='APPROVED' where id=%s", v1) == 'LX011')
check('viewer-less outsider sees nothing to decide', q(cx, "update resource_versions set status='APPROVED' where id=%s returning id", v1) == [])
cw = conn('worker', own)
check('a background job can never approve', fails(cw, "update resource_versions set status='APPROVED' where id=%s", v1) == 'LX011')
check('nor can the system', fails(sysc, "update resource_versions set status='REJECTED' where id=%s", v1) == 'LX011')
check('reviewer approves', len(q(cr, "update resource_versions set status='APPROVED' where id=%s returning id", v1)) == 1)
check('reviewer can record the decision', fails(cr, "insert into review_events(id,project_id,resource_id,version_id,action,previous_status,new_status,comment) values (%s,%s,%s,%s,'APPROVED','IN_REVIEW','APPROVED','ok')", uuid.uuid4(), proj, res, v1) is None)
check('decision record cannot be edited', q(cr, "update review_events set comment='changed' where version_id=%s returning id", v1) == [])
check('decision record cannot be deleted', q(ca, "delete from review_events where version_id=%s returning id", v1) == [])
check('approved content is frozen', fails(co, "update resource_versions set content='{}' where id=%s", v1) == 'LX010')

# owner authors v2; strict setting on by default
v2 = uuid.uuid4()
q(co, V, v2, res, 2, 'DRAFT', 'EDIT', '{"a":4}', 'i'*64, v1)
q(co, "update resource_versions set status='IN_REVIEW' where id=%s", v2)
check('owner cannot approve own version', fails(co, "update resource_versions set status='APPROVED' where id=%s", v2) == 'LX012')
va = uuid.uuid4(); q(ca, V, va, res, 7, 'IN_REVIEW', 'EDIT', '{}', 'z'*64, v1)
check('admin cannot approve own version either', fails(ca, "update resource_versions set status='REJECTED' where id=%s", va) == 'LX012')
q(sysc, "delete from resource_versions where id=%s", va)
check('two approved versions refused', fails(cr, "update resource_versions set status='APPROVED' where id=%s", v2) == '23505')
# approve with edits: archive old approved, insert reviewer version approved, archive reviewed one
q(cr, "update resource_versions set status='ARCHIVED' where id=%s", v1)
v3 = uuid.uuid4()
check('reviewer approves with edits (new approved version)', fails(cr, V, v3, res, 3, 'APPROVED', 'REVIEWER', '{"a":5}', 'j'*64, v2) is None)
check('the edited version is by the reviewer', q(sysc, "select created_by from resource_versions where id=%s", v3)[0][0] == rev)
check('cannot start approved without a reviewed version', fails(cr, V, uuid.uuid4(), res, 9, 'APPROVED', 'REVIEWER', '{}', 'k'*64, None) in ('LX011', '23505'))
q(cr, "update resource_versions set status='ARCHIVED' where id=%s", v3)
v4 = uuid.uuid4(); q(cr, V, v4, res, 4, 'DRAFT', 'EDIT', '{}', 'l'*64, v3)
q(cr, "update resource_versions set status='IN_REVIEW' where id=%s", v4)
check('reviewer cannot approve own edits made as a draft', fails(cr, "update resource_versions set status='APPROVED' where id=%s", v4) == 'LX012')
check('approving with edits on your own version refused', fails(cr, V, uuid.uuid4(), res, 5, 'APPROVED', 'REVIEWER', '{}', 'm'*64, v4) == 'LX012')

# admin turns the rule off for a one-person project
q(ca, "update projects set reviewer_not_author=false where id=%s", proj)
check('with the rule off, the author may approve', len(q(cr, "update resource_versions set status='APPROVED' where id=%s returning id", v4)) == 1)
v5 = uuid.uuid4(); q(ce, V, v5, res, 5, 'IN_REVIEW', 'EDIT', '{}', 'n'*64, v4)
check('editor still cannot decide with the rule off', fails(ce, "update resource_versions set status='CHANGES_REQUESTED' where id=%s", v5) == 'LX011')

# visibility
check('outsider sees no resource', q(cx, "select count(*) from resources where id=%s", res)[0][0] == 0)
check('outsider sees no versions', q(cx, "select count(*) from resource_versions where resource_id=%s", res)[0][0] == 0)
check('outsider sees no decisions', q(cx, "select count(*) from review_events where resource_id=%s", res)[0][0] == 0)
check('reviewer sees decisions', q(cr, "select count(*) from review_events where resource_id=%s", res)[0][0] == 1)
check('event cannot point at another project', fails(cr, "insert into review_events(id,project_id,resource_id,version_id,action,new_status) values (%s,%s,%s,%s,'SUBMITTED','IN_REVIEW')",
      uuid.uuid4(), q(sysc, "select id from projects where id<>%s limit 1", proj)[0][0], res, v1) == '42501')

# jobs: one at a time
J = "insert into analysis_jobs(id,project_id,kind,input) values (%s,%s,'SYLLABUS','{}')"
check('editor queues an analysis', fails(ce, J, uuid.uuid4(), proj) is None)
check('a second running analysis refused', fails(co, J, uuid.uuid4(), proj) == '23505')
check('reviewer cannot queue', fails(cr, J, uuid.uuid4(), proj) == '42501')

q(sysc, "delete from projects where id=%s", proj)
check('deleting the project removes everything', q(sysc, "select count(*) from resources where id=%s", res)[0][0] == 0)
q(sysc, "set session_replication_role = replica")
for u in (own, ed, rev, out): q(sysc, "delete from users where id=%s", u)
print(f"V11: {ok} checks passed")
