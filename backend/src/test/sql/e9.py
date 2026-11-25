"""V17 and E9 on real PostgreSQL with the exact SQL in Authorisations.java and YouTubeStore.java:
authorisation records ("nothing uploads without an authorisation record"), public confirmation,
education publications (owner or administrator, approved video, its checked render, rights),
publication status for project readers, analytics snapshots and reach reports."""
import _env
import psycopg, uuid, sys, re, json, datetime as dt
DSN = _env.DSN
SRC = _env.SRC
ACT_SQL = re.search(r'ACT_SQL = """(.*?)""";', open(SRC+'access/ActorDataSource.java').read(), re.S).group(1).replace('?', '%s')
def text(path): return open(SRC+path).read()
def block(path, name):
    m = re.search(name + r' = """(.*?)""";', text(path), re.S); assert m, name; return m.group(1).replace('?', '%s')
def inline(path, *needles):
    hits = [m for m in re.findall(r'"""(.*?)"""', text(path), re.S) if all(n in m for n in needles)]
    assert len(hits) == 1, (needles, hits); return hits[0].replace('?', '%s')
def joined(path, *needles):
    runs = [''.join(re.findall(r'"([^"]*)"', m)) for m in re.findall(r'"[^"]*"(?:\s*\+\s*"[^"]*")+', text(path))]
    hits = [r for r in runs if all(n in r for n in needles)]
    assert len(hits) == 1, (needles, hits); return hits[0].replace('?', '%s')

A = 'publish/Authorisations.java'; Y = 'youtube/YouTubeStore.java'; RS = 'approvals/ReviewStore.java'; V = 'videos/VideoStore.java'
AUTH = joined(A, 'insert into publish_authorisations'); MATCHES = joined(A, 'leenax_settings_hash(t.settings)')
PUBS = block(Y, 'PUBLICATIONS'); ADD_PUB = block(Y, 'ADD_PUBLICATION'); PUBLISHED = block(Y, 'PUBLISHED')
ADD_SNAP = block(Y, 'ADD_SNAPSHOT'); LATEST = block(Y, 'LATEST_SET'); SETS = block(Y, 'SETS'); REACH = block(Y, 'REACH')
SET_AT = inline(Y, 'where s.project_id = ? and s.social_account_id = ? and s.fetched_at = ?')
SAVE_JOB = inline(Y, 'insert into youtube_reporting_jobs'); INS_REACH = inline(Y, 'insert into youtube_reach_daily')
INS_REPORT = inline(Y, 'insert into youtube_reach_reports')
INS_RES = re.findall(r'"(insert into resources[^"]*)"', text(RS))[0].replace('?', '%s')
MOVE = re.findall(r'"(update resource_versions set status = \?[^"]*)"', text(RS))[0].replace('?', '%s')
INS_VER = re.search(r'"""(\s*insert into resource_versions.*?)"""', text(RS), re.S).group(1).replace('?', '%s')
RECORD = re.findall(r'"(insert into video_renders[^"]*)"', text(V))[0].replace('?', '%s')

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
owner, editor, viewer = mk('Owner'), mk('Editor'), mk('Viewer')
outsider = mk('Outsider')
co, ce, cv, cx, ca = conn('user', owner), conn('user', editor), conn('user', viewer), conn('user', outsider), conn('user', admin)
worker = conn('worker')
proj = uuid.uuid4()
co.execute("insert into projects(id,name,kind) values (%s,%s,'EDUCATION')", (proj, 'Publish ' + tag))
co.execute("insert into project_members(project_id,user_id,role,added_by) values (%s,%s,'EDITOR',%s),(%s,%s,'VIEWER',%s)", (proj, editor, owner, proj, viewer, owner))
acct = uuid.uuid4()
sysc.execute("insert into social_accounts(id,platform,external_id,display_name,status,scopes) values (%s,'YOUTUBE',%s,'Leenax','CONNECTED','x')", (acct, 'UC' + tag))

def asset(c, project=proj):
    a = uuid.uuid4()
    c.execute("insert into media_assets(id,project_id,kind,original_filename,content_type,size_bytes,checksum_sha256,storage_key) values (%s,%s,'VIDEO','v.mp4','video/mp4',1,'x',%s)", (a, project, 'k/' + a.hex)); return a
def post(c, media, settings, status='DRAFT'):
    p, t = uuid.uuid4(), uuid.uuid4()
    c.execute("insert into posts(id,title,media_id,status) values (%s,'Ohm',%s,'DRAFT')", (p, media))
    c.execute("insert into post_targets(id,post_id,account_id,platform,account_name,status,settings) values (%s,%s,%s,'YOUTUBE','Leenax',%s,%s)",
              (t, p, acct, status, json.dumps(settings)))
    return p, t
def authorise(c, target, public=False, project=None, version=None, rights=False, statement='Authorised posting “Ohm” to YouTube (Leenax).'):
    return fails(c, AUTH, uuid.uuid4(), target, public, rights, project, version, statement)
def go(c, target, status='UPLOADING'):
    return fails(c, "update post_targets set status=%s where id=%s", status, target)

# ---- creator posts: nothing uploads without an authorisation record -----------------------------------
m1 = asset(co)
unlisted = {"title": "Ohm", "privacy": "unlisted", "madeForKids": False}
p1, t1 = post(co, m1, unlisted, 'SCHEDULED')
check('a scheduled target with no authorisation can\'t start uploading', go(worker, t1) == 'LX020')
check('…nor be marked published', go(worker, t1, 'PUBLISHED') == 'LX020')
check('…nor be inserted as uploading', fails(co, "insert into post_targets(post_id,account_id,platform,account_name,status) values (%s,%s,'YOUTUBE','L','UPLOADING')", p1, acct) == 'LX020')
check('the upload check finds nothing', co.execute(MATCHES, (t1,)).fetchone()[0] == 0)
check('the worker can\'t authorise', authorise(worker, t1) == 'LX020')
check('someone else can\'t authorise another person\'s post', authorise(ca, t1) == 'LX020')
check('a statement is required', authorise(co, t1, statement='ok') == '23514')
check('the person posting authorises it', authorise(co, t1) is None)
row = co.execute("select media_id, platform, public, authorised_by, settings_hash = leenax_settings_hash(%s::jsonb) from publish_authorisations where post_target_id=%s",
                 (json.dumps(unlisted), t1)).fetchone()
check('the database fills in the file, platform, public flag, person and settings hash', row == (m1, 'YOUTUBE', False, owner, True), row)
check('the upload check matches', co.execute(MATCHES, (t1,)).fetchone()[0] == 1)
check('one authorisation per target', authorise(co, t1) == '23505')
check('an authorisation can\'t be changed', fails(sysc, "update publish_authorisations set public_confirmed = true where post_target_id=%s", t1) == 'LX023')
check('now it can upload', go(worker, t1) is None)
check('…and go on processing and publishing', go(worker, t1, 'PROCESSING') is None and go(worker, t1, 'PUBLISHED') is None)

p2, t2 = post(co, m1, unlisted, 'SCHEDULED'); authorise(co, t2)
co.execute("update post_targets set settings = settings || '{\"title\":\"Ohm’s law, changed\"}' where id=%s", (t2,))
check('changed settings after authorising: no upload', go(worker, t2) == 'LX020' and co.execute(MATCHES, (t2,)).fetchone()[0] == 0)
p3, t3 = post(co, m1, unlisted, 'SCHEDULED'); authorise(co, t3)
co.execute("update posts set media_id=%s where id=%s", (asset(co), p3))
check('a different file after authorising: no upload', go(worker, t3) == 'LX020')

pub = {"title": "Ohm", "privacy": "public", "madeForKids": False}
p4, t4 = post(co, m1, pub, 'SCHEDULED')
check('a public post needs its confirmation', authorise(co, t4) == 'LX021')
check('…and is authorised with it', authorise(co, t4, public=True) is None and co.execute("select public from publish_authorisations where post_target_id=%s", (t4,)).fetchone()[0])
sched = {"title": "Ohm", "privacy": "private", "publishAt": "2027-01-01T00:00:00Z", "madeForKids": False}
p5, t5 = post(co, m1, sched, 'SCHEDULED')
check('a private video YouTube makes public later counts as public', authorise(co, t5) == 'LX021')
tk = sysc.execute("select leenax_target_is_public('TIKTOK','{\"privacy\":\"SELF_ONLY\"}'), leenax_target_is_public('TIKTOK','{\"privacy\":\"PUBLIC_TO_EVERYONE\"}'), leenax_target_is_public('INSTAGRAM','{}')").fetchone()
check('TikTok "only me" is private, other platforms are public', tk == (False, True, True), tk)
check('an outsider doesn\'t see the authorisation', cx.execute("select count(*) from publish_authorisations where post_target_id=%s", (t1,)).fetchone()[0] == 0)
check('targets already uploading before V17 carry on', go(worker, t1, 'PUBLISHED') is None)

# ---- education publications ----------------------------------------------------------------------------
lesson = uuid.uuid4()
co.execute(INS_RES, (lesson, proj, 'LESSON_VIDEO', 'Ohm: beginner lesson video', 't1', 'BEGINNER'))
def version(status='DRAFT', h=None):
    v = uuid.uuid4(); h = h or uuid.uuid4().hex * 2
    sysc.execute("set session_replication_role = replica")   # fixture: E8's tests cover how a video gets approved
    sysc.execute(INS_VER, (v, lesson, 1 + sysc.execute("select count(*) from resource_versions where resource_id=%s", (lesson,)).fetchone()[0], status, 'MANUAL', '{"kind":"LESSON"}', h, None, None))
    sysc.execute("set session_replication_role = origin")
    return v, h
lv, lh = version('APPROVED')     # approved directly here: E8's tests cover how a video gets approved
tl = uuid.uuid4()
co.execute("insert into timelines (id, project_id, name, width, height, fps, document) values (%s,%s,'lesson',1920,1080,30,'{}')", (tl, proj))
def render(ok_, media, h):
    j = uuid.uuid4()
    co.execute("insert into render_jobs (id, timeline_id, timeline_name, project_id, status, settings, document, validation, output_media_id) values (%s,%s,'l',%s,'COMPLETED','{}',%s,%s,%s)",
               (j, tl, proj, json.dumps({"tracks": [{"clips": [{"type": "text", "caption": True, "start": 0.5, "duration": 2, "text": "Hi"}]}]}), json.dumps({"ok": ok_}), media))
    co.execute(RECORD, (uuid.uuid4(), proj, lesson, lv, h, j, tl)); return j
checked_media, failed_media, other_media = asset(co), asset(co), asset(co)
good_job = render(True, checked_media, lh); render(False, failed_media, lh); render(True, other_media, 'a' * 64)

edu = {"title": "Ohm's law explained", "privacy": "unlisted", "madeForKids": False, "categoryId": "27"}
pe, te = post(co, checked_media, edu, 'SCHEDULED')
check('an education authorisation names its version', authorise(co, te, project=proj, rights=True) == 'LX022')
check('the rights must be confirmed', authorise(co, te, project=proj, version=lv) == 'LX022')
pf, tf = post(co, failed_media, edu, 'SCHEDULED')
check('a render that failed its checks can\'t be published', authorise(co, tf, project=proj, version=lv, rights=True) == 'LX022')
po, to_ = post(co, other_media, edu, 'SCHEDULED')
check('a passing render of other content can\'t be published', authorise(co, to_, project=proj, version=lv, rights=True) == 'LX022')
pd_, td = post(ce, checked_media, edu, 'SCHEDULED')
check('an editor can\'t authorise YouTube publishing', authorise(ce, td, project=proj, version=lv, rights=True) == 'LX022')
dv, dh = version('DRAFT')
check('a draft version can\'t be published', authorise(co, te, project=proj, version=dv, rights=True) == 'LX022')
check('the owner authorises the approved video\'s checked render', authorise(co, te, project=proj, version=lv, rights=True) is None)
check('the resource is filled in', co.execute("select resource_id, rights_confirmed from publish_authorisations where post_target_id=%s", (te,)).fetchone() == (lesson, True))
pa, ta = post(ca, checked_media, edu, 'SCHEDULED')
check('an administrator can authorise it too', authorise(ca, ta, project=proj, version=lv, rights=True) is None)

pubid = uuid.uuid4()
check('an editor can\'t record a publication', fails(ce, "insert into youtube_publications (id, project_id, resource_id, version_id, topic_id, title) values (%s,%s,%s,%s,'t1','x')",
                                                    uuid.uuid4(), proj, lesson, lv) == '42501')
co.execute(ADD_PUB, (pubid, proj, lesson, lv, 't1', "Ohm's law explained", te))
r = co.execute("select post_id, authorisation_id is not null, social_account_id from youtube_publications where id=%s", (pubid,)).fetchone()
check('the publication links its post, authorisation and channel', r == (pe, True, acct), r)
check('a publication needs an authorised target', co.execute(ADD_PUB, (uuid.uuid4(), proj, lesson, lv, 't1', 'x', tf)).rowcount == 0)
check('one publication per target', fails(co, ADD_PUB, uuid.uuid4(), proj, lesson, lv, 't1', 'x', te) == '23505')

go(worker, te); worker.execute("update post_targets set status='PUBLISHED', remote_id='vidE9', remote_url='https://www.youtube.com/watch?v=vidE9', published_at=now() where id=%s", (te,))
rows = cv.execute(PUBS, (proj, proj)).fetchall()
check('a viewer sees the publication and how it went, though posts are personal', len(rows) == 1 and rows[0][10] == 'PUBLISHED' and rows[0][11] == 'vidE9', rows)
check('…with who authorised it and what they confirmed', rows[0][18] == 'Owner' and rows[0][20] is True and 'YouTube' in rows[0][19], rows[0][17:])
check('…and the settings without the description or captions', 'title' in json.loads(rows[0][16]) and 'description' not in json.loads(rows[0][16]))
check('the viewer can\'t see the post itself', cv.execute("select count(*) from posts where id=%s", (pe,)).fetchone()[0] == 0)
check('an outsider sees nothing', cx.execute(PUBS, (proj, proj)).fetchall() == [] and cx.execute("select count(*) from leenax_youtube_publication_status(%s)", (proj,)).fetchone()[0] == 0)
pubd = cv.execute(PUBLISHED, (proj, proj)).fetchall()
check('published videos are listed for analytics', [x[1] for x in pubd] == ['vidE9'] and pubd[0][6] == acct, pubd)
check('the checked render\'s timeline is readable for captions', json.loads(co.execute("select document::text from render_jobs where id=%s", (good_job,)).fetchone()[0])['tracks'][0]['clips'][0]['text'] == 'Hi')

# ---- analytics snapshots ---------------------------------------------------------------------------------------
d = dt.date
at1 = dt.datetime(2026, 11, 20, 10, 0, tzinfo=dt.timezone.utc); at2 = at1 + dt.timedelta(hours=5)
data = json.dumps({"source": "YouTube Analytics API (reports.query)", "columns": ["video", "views"], "rows": [["vidE9", 120]]})
for at in (at1, at2):
    for kind, vid in (('BASIC', None), ('TRAFFIC', None), ('RETENTION', 'vidE9')):
        cv.execute(ADD_SNAP, (uuid.uuid4(), proj, acct, kind, vid, d(2026, 10, 23), d(2026, 11, 19), data, at))
cv.execute(ADD_SNAP, (uuid.uuid4(), proj, acct, 'BASIC', None, d(2026, 9, 1), d(2026, 9, 30), data, at1))
check('a snapshot\'s period runs forward', fails(cv, ADD_SNAP, uuid.uuid4(), proj, acct, 'BASIC', None, d(2026, 11, 2), d(2026, 11, 1), data, at1) == '23514')
check('only known kinds', fails(cv, ADD_SNAP, uuid.uuid4(), proj, acct, 'COMPETITORS', None, d(2026, 11, 1), d(2026, 11, 2), data, at1) == '23514')
latest = cv.execute(LATEST, (proj, acct, d(2026, 10, 23), d(2026, 11, 19)) * 2).fetchall()
check('the newest set of a period comes back whole', len(latest) == 3 and all(x[8] == at2 for x in latest), [(x[3], x[8]) for x in latest])
check('…with who took it', latest[0][9] == 'Viewer')
check('an older set is kept as it was', len(cv.execute(SET_AT, (proj, acct, at1)).fetchall()) == 4)
sets = cv.execute(SETS, (proj, None, None, None, None)).fetchall()
check('sets are listed newest first', [(x[3], x[5]) for x in sets] == [(at2, 3), (at1, 3), (at1, 1)], sets)
f = cv.execute(SETS, (proj, d(2026, 9, 10), d(2026, 9, 10), d(2026, 9, 12), d(2026, 9, 12))).fetchall()
check('date filtering finds sets overlapping the period', len(f) == 1 and f[0][1] == d(2026, 9, 1), f)

# ---- reach reports ---------------------------------------------------------------------------------------------
cv.execute(SAVE_JOB, (acct, 'channel_reach_basic_a1', 'job1'))
cv.execute(SAVE_JOB, (acct, 'channel_reach_basic_a1', 'job2'))
check('one reporting job per channel, replaced when made again', cv.execute("select job_id from youtube_reporting_jobs where social_account_id=%s", (acct,)).fetchall() == [('job2',)])
def save(report, rows, day):
    with psycopg.connect(DSN) as c:      # one transaction, like the service
        c.execute(ACT_SQL, ('worker', None, 'worker', 'worker', 'worker', None, None))
        c.execute("delete from youtube_reach_daily where social_account_id = %s and day = %s", (acct, day))
        for v, n, ctr in rows: c.execute(INS_REACH, (acct, day, v, n, ctr, report))
        c.execute(INS_REPORT, (acct, report, at1, at1, at1, len(rows)))
check('the save in YouTubeStore deletes the day first', 'delete from youtube_reach_daily where social_account_id = ? and day = ?' in text(Y))
save('r1', [('vidE9', 1000, 0.05), ('vidX', 10, None)], d(2026, 11, 18))
save('r2', [('vidE9', 3000, 0.01)], d(2026, 11, 19))
save('r3', [('vidE9', 2000, 0.04)], d(2026, 11, 18))    # YouTube's corrected file for the 18th
reach = {x[0]: x for x in cv.execute(REACH, (acct, d(2026, 11, 1), d(2026, 11, 30))).fetchall()}
check('a backfill replaces its day', reach['vidE9'][1] == 5000 and 'vidX' not in reach, reach)
check('click-through rate is weighted by impressions', abs(reach['vidE9'][2] - (2000 * 0.04 + 3000 * 0.01) / 5000) < 1e-9, reach['vidE9'])
check('…over the days it covers', reach['vidE9'][3:] == (d(2026, 11, 18), d(2026, 11, 19), 2), reach['vidE9'])
check('a rate above 100% is refused', fails(cv, INS_REACH, acct, d(2026, 11, 17), 'v', 1, 1.5, 'r9') == '23514')
check('reports are imported once', fails(cv, "insert into youtube_reach_reports (social_account_id, report_id, start_time, end_time, create_time, rows) values (%s,'r1',now(),now(),now(),0)", acct) == '23505')

# deleting a project keeps its authorisations (as records), without the link
auths = sysc.execute("select count(*) from publish_authorisations where project_id=%s", (proj,)).fetchone()[0]
check('a project can be deleted though it has authorisations', fails(sysc, "delete from projects where id=%s", proj) is None)
check('…which stay, unlinked', sysc.execute("select count(*) from publish_authorisations where post_target_id in (%s,%s) and project_id is null and version_id is null", (te, ta)).fetchone()[0] == 2 and auths == 2)
check('…and still can\'t be edited', fails(sysc, "update publish_authorisations set statement = 'changed statement' where post_target_id=%s", te) == 'LX023')
# clean up
sysc.execute("set session_replication_role = replica")
for p in (p1, p2, p3, p4, p5, pe, pf, po, pd_, pa): sysc.execute("delete from posts where id=%s", (p,))
sysc.execute("delete from social_accounts where id=%s", (acct,))
sysc.execute("delete from media_assets where project_id is null and storage_key like 'k/%%'")
for u in (owner, editor, viewer, outsider): sysc.execute("delete from users where id=%s", (u,))
print(f"E9 (V17) end to end: {ok} checks passed")
