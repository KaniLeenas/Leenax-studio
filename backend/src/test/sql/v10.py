"""Real PostgreSQL checks for V10: course documents follow project access."""
import _env
import psycopg, uuid, sys, re
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

sysc = conn('system')
admin = q(sysc, "select id from users where role='ADMIN' and status='ACTIVE' limit 1")[0][0]
q(sysc, "update users set status='DEACTIVATED' where id <> %s and status <> 'DEACTIVATED'", admin)
tag = uuid.uuid4().hex[:6]
def mk(n): return q(sysc, "insert into users(email,display_name,password_hash) values (%s,%s,'x') returning id", f"{n}-{tag}@t.lk", n)[0][0]
own, view, edit, out = mk('Owner'), mk('Viewer'), mk('Editor'), mk('Outsider')
co, cv, ce, cx, ca = conn('user', own), conn('user', view), conn('user', edit), conn('user', out), conn('user', admin)
proj = uuid.uuid4()
q(co, "insert into projects(id,name,kind) values (%s,%s,'EDUCATION')", proj, 'DBMS '+tag)
q(co, "insert into project_members(project_id,user_id,role,added_by) values (%s,%s,'VIEWER',%s),(%s,%s,'EDITOR',%s)", proj, view, own, proj, edit, own)

doc = uuid.uuid4(); ver = uuid.uuid4()
q(co, "insert into documents(id,project_id,title) values (%s,%s,'Lecture 3')", doc, proj)
check('creator is recorded', q(sysc, "select created_by from documents where id=%s", doc)[0][0] == own)
q(co, """insert into document_versions(id,document_id,version_no,original_filename,content_type,size_bytes,sha256,storage_key)
         values (%s,%s,1,'l3.pdf','application/pdf',100,%s,'documents/x.pdf')""", ver, doc, 'a'*64)
check('editor can add a version', fails(ce, """insert into document_versions(id,document_id,version_no,original_filename,content_type,size_bytes,sha256,storage_key)
         values (%s,%s,2,'l3.pdf','application/pdf',100,%s,'documents/y.pdf')""", uuid.uuid4(), doc, 'b'*64) is None)
check('viewer cannot add a version', fails(cv, """insert into document_versions(id,document_id,version_no,original_filename,content_type,size_bytes,sha256,storage_key)
         values (%s,%s,3,'l3.pdf','application/pdf',100,%s,'documents/z.pdf')""", uuid.uuid4(), doc, 'c'*64) == '42501')
check('viewer cannot add a document', fails(cv, "insert into documents(id,project_id,title) values (%s,%s,'x')", uuid.uuid4(), proj) == '42501')
check('outsider cannot add a document', fails(cx, "insert into documents(id,project_id,title) values (%s,%s,'x')", uuid.uuid4(), proj) == '42501')

# The worker writes pages and passages as the owner.
cw = conn('worker', own)
q(cw, "insert into document_pages(version_id,page_no,method,text) values (%s,1,'TEXT','Normalisation தரவுத்தள')", ver)
q(cw, "insert into document_chunks(version_id,page_no,ordinal,text,terms) values (%s,1,0,'Normalisation தரவுத்தள', %s)", ver, ['normalisation','தரவுத்தள'])
q(cw, "update document_versions set status='READY' where id=%s", ver)
check('owner cannot write pages directly', fails(co, "insert into document_pages(version_id,page_no,method,text) values (%s,2,'TEXT','x')", ver) == '42501')
check('editor cannot write passages directly', fails(ce, "insert into document_chunks(version_id,page_no,ordinal,text,terms) values (%s,1,9,'x','{x}')", ver) == '42501')

for name, c in [('owner', co), ('viewer', cv), ('editor', ce), ('admin', ca)]:
    check(f'{name} sees the document', q(c, "select count(*) from documents where id=%s", doc)[0][0] == 1)
    check(f'{name} sees its passages', q(c, "select count(*) from document_chunks where version_id=%s", ver)[0][0] == 1)
check('outsider sees no document', q(cx, "select count(*) from documents where id=%s", doc)[0][0] == 0)
check('outsider sees no version', q(cx, "select count(*) from document_versions where id=%s", ver)[0][0] == 0)
check('outsider sees no pages', q(cx, "select count(*) from document_pages where version_id=%s", ver)[0][0] == 0)
check('outsider sees no passages', q(cx, "select count(*) from document_chunks where version_id=%s", ver)[0][0] == 0)
check('unset connection sees nothing', q(psycopg.connect(DSN, autocommit=True) if False else conn('anon'), "select count(*) from document_chunks")[0][0] == 0)

# Search as the app runs it: Tamil prefix match, through RLS.
SEARCH = "select count(*) from document_chunks c where exists (select 1 from unnest(c.terms) t where t like %s)"
check('Tamil prefix search finds the passage', q(cv, SEARCH, 'தரவு%')[0][0] == 1)
check('outsider search finds nothing', q(cx, SEARCH, 'தரவு%')[0][0] == 0)

check('viewer cannot rename', q(cv, "update documents set title='x' where id=%s returning id", doc) == [])
check('editor can rename', len(q(ce, "update documents set title='Lecture 3 (v2)' where id=%s returning id", doc)) == 1)
check('viewer cannot delete', q(cv, "delete from documents where id=%s returning id", doc) == [])
check('outsider cannot delete', q(cx, "delete from documents where id=%s returning id", doc) == [])
check('owner deletes, pages and passages go with it', len(q(co, "delete from documents where id=%s returning id", doc)) == 1)
check('nothing left behind', q(sysc, "select count(*) from document_chunks where version_id=%s", ver)[0][0] == 0
      and q(sysc, "select count(*) from document_pages where version_id=%s", ver)[0][0] == 0)

check('a document needs a project', fails(sysc, "insert into documents(id,title) values (%s,'x')", uuid.uuid4()) == '23502')
q(sysc, "delete from projects where id=%s", proj)
q(sysc, "set session_replication_role = replica")
for u in (own, view, edit, out): q(sysc, "delete from users where id=%s", u)
print(f"V10: {ok} checks passed")
