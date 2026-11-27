"""Real PostgreSQL checks for account lifecycle, administrators, and row-level security."""
import _env
import psycopg, uuid, sys
DSN = _env.DSN

# The statement ActorDataSource sends, read from the Java source so the test can't drift from it.
import re
_java = open(_env.SRC + 'access/ActorDataSource.java').read()
ACT_SQL = re.search(r'ACT_SQL = """(.*?)""";', _java, re.S).group(1).replace('?', '%s')

def conn(mode='system', uid=None, email=None):
    c = psycopg.connect(DSN, autocommit=True)
    u = str(uid) if uid else None
    c.execute(ACT_SQL, (mode, u if mode != 'user' else None, mode, mode, mode, u, email))
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

def fails(c, sql, *p, code=None):
    try:
        q(c, sql, *p); return None
    except psycopg.Error as e:
        return e.sqlstate

sysc = conn('system')
check('system runs as the login role', q(sysc, "select current_user")[0][0] == 'leenax')
admin = q(sysc, "select id from users where role='ADMIN' limit 1")[0][0]
acct = (q(sysc, "select id from ai_accounts limit 1") or q(sysc, "insert into ai_accounts(id,provider,name) values (gen_random_uuid(),'ELEVENLABS','Test 11L') returning id"))[0][0]
tag = uuid.uuid4().hex[:6]
def mkuser(name, role='MEMBER', status='ACTIVE', create=True):
    return q(sysc, "insert into users(email, display_name, password_hash, role, status, can_create_projects) values (%s,%s,'x',%s,%s,%s) returning id",
             f"{name}-{tag}@t.lk", name, role, status, create)[0][0]

# ---- account lifecycle; V20 removes the old fixed seat ceiling ----
q(sysc, "update users set status='DEACTIVATED' where id <> %s and status <> 'DEACTIVATED'", admin)
a = mkuser('Asha'); b = mkuser('Bala'); c3 = mkuser('Chitra', status='INVITED'); d = mkuser('Devi', create=False)
bulk = [mkuser(f'Bulk{i}') for i in range(100)]
check('more than 50 active accounts are accepted', len(bulk) == 100 and q(sysc, "select count(*) from users where status='ACTIVE'")[0][0] >= 104)
check('additional invitations are accepted', mkuser('InviteBeyondLimit', status='INVITED') is not None)
gone = mkuser('Ghost', status='DEACTIVATED')
check('deactivated accounts can be reactivated without a seat check', q(sysc, "update users set status='ACTIVE' where id=%s returning id", gone) == [(gone,)])
check('the last admin cannot be demoted', fails(sysc, "update users set role='MEMBER' where id=%s", admin) == 'LX002')
check('the last admin cannot be deactivated', fails(sysc, "update users set status='DEACTIVATED' where id=%s", admin) == 'LX002')
check('users are never deleted', fails(sysc, "delete from users where id=%s", d) == 'LX003')
check('an active user must have a password', fails(sysc, "update users set password_hash=null where id=%s", a) == '23514')

# ---- connections as people ----
ca = conn('user', a); cb = conn('user', b); cd = conn('user', d); cadm = conn('user', admin)
check('a person runs as the restricted role', q(ca, "select current_user")[0][0] == 'leenax_app')
check('mode and id resolve from the users table', q(ca, "select leenax_mode(), leenax_uid()")[0] == ('user', a))
check('an administrator resolves to admin', q(cadm, "select leenax_mode()")[0][0] == 'admin')
check('lookup by email works too', q(conn('user', None, f"Bala-{tag}@T.LK"), "select leenax_uid()")[0][0] == b)
check('an invited user is treated as signed out', q(conn('user', c3), "select leenax_mode()")[0][0] == 'anon')
raw = psycopg.connect(DSN, autocommit=True); raw.execute("set role leenax_app")
check('a connection that skipped the step sees nothing', q(raw, "select count(*) from projects")[0][0] == 0)

# ---- projects ----
pa = uuid.uuid4()   # like Hibernate: id made in the app, plain INSERT without RETURNING
q(ca, "insert into projects(id, name) values (%s, 'DBMS ' || %s)", pa, tag)
check('the creator becomes owner', q(sysc, "select role from project_members where project_id=%s and user_id=%s", pa, a)[0][0] == 'OWNER')
check('others cannot see the project', q(cb, "select count(*) from projects where id=%s", pa)[0][0] == 0)
check('admin sees every project', q(cadm, "select count(*) from projects where id=%s", pa)[0][0] == 1)
check('without permission a project cannot be created', fails(cd, "insert into projects(name) values ('x')") == '42501')
check('signed-out sees nothing', q(conn('anon'), "select count(*) from projects")[0][0] == 0)

m1 = q(ca, "insert into media_assets(kind,source,original_filename,content_type,size_bytes,checksum_sha256,storage_key,project_id) values ('IMAGE','UPLOAD','a.png','image/png',1,'c',%s,%s) returning id, created_by", 'k-' + tag, pa)[0]
check('new rows record their creator', m1[1] == a)
check('a non-member cannot see project media', q(cb, "select count(*) from media_assets where id=%s", m1[0])[0][0] == 0)
check('a non-member cannot add to the project', fails(cb, "insert into media_assets(kind,source,original_filename,content_type,size_bytes,checksum_sha256,storage_key,project_id) values ('IMAGE','UPLOAD','b.png','image/png',1,'c',%s,%s)", 'k2-' + tag, pa) == '42501')

q(ca, "insert into project_members(project_id,user_id,role,added_by) values (%s,%s,'VIEWER',%s)", pa, b, a)
check('a viewer sees the project', q(cb, "select count(*) from projects where id=%s", pa)[0][0] == 1)
check('a viewer sees its media', q(cb, "select count(*) from media_assets where id=%s", m1[0])[0][0] == 1)
check('a viewer cannot change media (no rows touched)', q(cb, "update media_assets set original_filename='x' where id=%s returning id", m1[0]) == [])
check('a viewer cannot delete media', q(cb, "delete from media_assets where id=%s returning id", m1[0]) == [])
check('a viewer cannot add media', fails(cb, "insert into media_assets(kind,source,original_filename,content_type,size_bytes,checksum_sha256,storage_key,project_id) values ('IMAGE','UPLOAD','b.png','image/png',1,'c',%s,%s)", 'k3-' + tag, pa) == '42501')
check('a viewer cannot rename the project', q(cb, "update projects set name='x' where id=%s returning id", pa) == [])
check('a viewer cannot move media out of the project', q(cb, "update media_assets set project_id=null where id=%s returning id", m1[0]) == [])
q(ca, "update project_members set role='EDITOR' where project_id=%s and user_id=%s", pa, b)
check('an editor can add media', q(cb, "insert into media_assets(kind,source,original_filename,content_type,size_bytes,checksum_sha256,storage_key,project_id) values ('IMAGE','UPLOAD','b.png','image/png',1,'c',%s,%s) returning id", 'k4-' + tag, pa) is not None)
check('an editor cannot delete the project', q(cb, "delete from projects where id=%s returning id", pa) == [])
check('an editor cannot move project media into their own space', fails(cb, "update media_assets set project_id=null where id=%s", m1[0]) == '42501')

# ---- things without a project ----
s1 = q(ca, "insert into scripts(title,language,video_type,body) values ('mine','TA','REEL','x') returning id")[0][0]
check('a personal script is hidden from others', q(cb, "select count(*) from scripts where id=%s", s1)[0][0] == 0)
check('admin sees personal scripts', q(cadm, "select count(*) from scripts where id=%s", s1)[0][0] == 1)
check("one cannot plant a row in someone else's name", fails(cb, "insert into scripts(title,language,video_type,body,created_by) values ('x','TA','REEL','x',%s)", a) == '42501')

# ---- children follow parents ----
bd = q(ca, "insert into storyboard_projects(title,aspect_ratio,language,project_id) values ('b','PORTRAIT','TA',%s) returning id", pa)[0][0]
sc = q(ca, "insert into storyboard_scenes(storyboard_id,position) values (%s,0) returning id", bd)[0][0]
q(ca, "update project_members set role='REVIEWER' where project_id=%s and user_id=%s", pa, b)
check('a reviewer sees scenes', q(cb, "select count(*) from storyboard_scenes where id=%s", sc)[0][0] == 1)
check('a reviewer cannot add scenes', fails(cb, "insert into storyboard_scenes(storyboard_id,position) values (%s,1)", bd) == '42501')
check('a reviewer cannot edit scenes', q(cb, "update storyboard_scenes set title='x' where id=%s returning id", sc) == [])
q(ca, "insert into storyboard_references(scene_id,media_id,position) values (%s,%s,0)", sc, m1[0])
check('scene references follow the scene', q(cb, "select count(*) from storyboard_references where scene_id=%s", sc)[0][0] == 1)
q(ca, "delete from project_members where project_id=%s and user_id=%s", pa, b)
check('removed members lose scenes', q(cb, "select count(*) from storyboard_scenes where id=%s", sc)[0][0] == 0)
check('removed members lose references', q(cb, "select count(*) from storyboard_references where scene_id=%s", sc)[0][0] == 0)

post = q(ca, "insert into posts(title,status,media_id) values ('p','DRAFT',%s) returning id", m1[0])[0][0]
q(ca, "insert into post_targets(post_id,platform,account_name,status) values (%s,'YOUTUBE','ch','DRAFT')", post)
check("others don't see your posts", q(cb, "select count(*) from posts where id=%s", post)[0][0] == 0)
check('post targets follow the post', q(cb, "select count(*) from post_targets where post_id=%s", post)[0][0] == 0)

# ---- voices ----
v = q(ca, "insert into voice_profiles(account_id,provider,provider_voice_id,name,kind) values (%s,'ELEVENLABS','x','Asha voice','STOCK') returning id", acct)[0][0]
check('a private voice is hidden', q(cb, "select count(*) from voice_profiles where id=%s", v)[0][0] == 0)
q(ca, "update voice_profiles set shared=true where id=%s", v)
check('a shared voice can be used by all', q(cb, "select count(*) from voice_profiles where id=%s", v)[0][0] == 1)
check('but only its creator changes it', q(cb, "update voice_profiles set name='x' where id=%s returning id", v) == [])

# ---- workers ----
w = conn('worker', a)
check('a worker sees every job to run it', q(w, "select count(*) from scripts where id=%s", s1)[0][0] == 1)
mw = q(w, "insert into media_assets(kind,source,original_filename,content_type,size_bytes,checksum_sha256,storage_key) values ('VIDEO','AI_GENERATED','v.mp4','video/mp4',1,'c',%s) returning created_by", 'kw-' + tag)[0][0]
check("a worker's output belongs to the job owner", mw == a)
check('usage is recorded against the acting user', q(w, "select leenax_uid()")[0][0] == a)

# ---- a pooled connection switching people ----
pool = conn('user', a)
check('pooled connection as Asha', q(pool, "select count(*) from scripts where id=%s", s1)[0][0] == 1)
pool.execute(ACT_SQL, ('user', None, 'user', 'user', 'user', str(b), None))
check('the same connection as Bala no longer sees it', q(pool, "select count(*) from scripts where id=%s", s1)[0][0] == 0)
pool.execute(ACT_SQL, ('system', None, 'system', 'system', 'system', None, None))
check('and back to system runs as the login role', q(pool, "select current_user")[0][0] == 'leenax')

# ---- deleting a project keeps its content with the creator ----
q(ca, "delete from projects where id=%s", pa)
check('content of a deleted project stays with its creator', q(ca, "select project_id from media_assets where id=%s", m1[0])[0][0] is None)
check('and stays hidden from others', q(cb, "select count(*) from media_assets where id=%s", m1[0])[0][0] == 0)

# tidy up (system)
q(sysc, "delete from posts where id=%s", post); q(sysc, "delete from storyboard_projects where id=%s", bd)
q(sysc, "delete from scripts where id=%s", s1); q(sysc, "delete from voice_profiles where id=%s", v)
q(sysc, "delete from media_assets where storage_key like %s", '%' + tag)
q(sysc, "update users set status='DEACTIVATED' where email like %s and status <> 'DEACTIVATED'", '%-' + tag + '@t.lk')
print(ok, 'V9 checks passed')
