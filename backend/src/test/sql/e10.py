"""E10 on real PostgreSQL: one subject taken through the whole workflow by real roles
(syllabus → topics → notes, questions, QA, SEO → presentation → lesson video with a checked
render → authorised YouTube publication → analytics snapshot), each gate refusing before its
step is approved; the notifications the database writes along the way; and the dashboard's
SQL in EducationDashboard.java and NotificationController.java, as each person sees it."""
import _env
import psycopg, uuid, sys, re, json, datetime as dt

DSN = _env.DSN
SRC = _env.SRC
ACT_SQL = re.search(r'ACT_SQL = """(.*?)""";', open(SRC + 'access/ActorDataSource.java').read(), re.S).group(1).replace('?', '%s')
def text(path): return open(SRC + path).read()
def block(path, name):
    m = re.search(name + r' = """(.*?)""";', text(path), re.S); assert m, name; return m.group(1).replace('?', '%s')
def joined(path, *needles):
    runs = [''.join(re.findall(r'"([^"]*)"', m)) for m in re.findall(r'"[^"]*"(?:\s*\+\s*"[^"]*")+', text(path))]
    hits = [r for r in runs if all(n in r for n in needles)]
    assert len(hits) == 1, (needles, hits); return hits[0].replace('?', '%s')
def plain(path, name):
    m = re.search(name + r' = "([^"]*)";', text(path)); assert m, name; return m.group(1).replace('?', '%s')

D = 'dashboard/EducationDashboard.java'; N = 'notifications/NotificationController.java'; RS = 'approvals/ReviewStore.java'
COUNTS, TASKS, ACTIVE, ACTIVITY = (block(D, n) for n in ('COUNTS', 'TASKS', 'ACTIVE', 'ACTIVITY'))
LIST, UNREAD, MARK, MARK_ALL = block(N, 'LIST'), plain(N, 'UNREAD'), plain(N, 'MARK'), plain(N, 'MARK_ALL')
AUTH = joined('publish/Authorisations.java', 'insert into publish_authorisations')
ADD_PUB = block('youtube/YouTubeStore.java', 'ADD_PUBLICATION'); ADD_SNAP = block('youtube/YouTubeStore.java', 'ADD_SNAPSHOT')
INS_RES = re.findall(r'"(insert into resources[^"]*)"', text(RS))[0].replace('?', '%s')
MOVE = re.findall(r'"(update resource_versions set status = \?[^"]*)"', text(RS))[0].replace('?', '%s')
INS_VER = re.search(r'"""(\s*insert into resource_versions.*?)"""', text(RS), re.S).group(1).replace('?', '%s')
EVENT = re.search(r'"""(\s*insert into review_events.*?)"""', text(RS), re.S).group(1).replace('?', '%s')
RECORD = re.findall(r'"(insert into video_renders[^"]*)"', text('videos/VideoStore.java'))[0].replace('?', '%s')

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
check('a sixth active person can\'t be added (five seats)', fails(sysc, "insert into users(email,display_name,password_hash) values (%s,'Sixth','x')", f"six-{tag}@t.lk") is not None)
co, cr, ce, cv = conn('user', owner), conn('user', reviewer), conn('user', editor), conn('user', viewer)
worker = conn('worker')
proj = uuid.uuid4()
co.execute("insert into projects(id,name,kind) values (%s,%s,'EDUCATION')", (proj, 'Circuits ' + tag))
co.execute("insert into project_members(project_id,user_id,role,added_by) values (%s,%s,'REVIEWER',%s),(%s,%s,'EDITOR',%s),(%s,%s,'VIEWER',%s)",
           (proj, reviewer, owner, proj, editor, owner, proj, viewer, owner))
co.execute("insert into documents(id,project_id,title) values (%s,%s,'Module handbook')", (uuid.uuid4(), proj))

def counts(c): return c.execute(COUNTS).fetchone()
def unread(c): return c.execute(UNREAD).fetchone()[0]
def notes_of(c): return c.execute(LIST, (False, 50)).fetchall()

# ---- the workflow ---------------------------------------------------------------------------------
def resource(kind, topic=None, level=None, title=None, by=ce):
    r = uuid.uuid4(); by.execute(INS_RES, (r, proj, kind, title or kind.title(), topic, level)); return r
def draft(r, content, by=ce):
    v = uuid.uuid4(); n = 1 + by.execute("select count(*) from resource_versions where resource_id=%s", (r,)).fetchone()[0]
    by.execute(INS_VER, (v, r, n, 'DRAFT', 'MANUAL', json.dumps(content), uuid.uuid4().hex * 2, None, None)); return v
def submit(r, v, by=ce):
    by.execute(MOVE, ('IN_REVIEW', v, 'DRAFT')); by.execute(EVENT, (uuid.uuid4(), proj, r, v, 'SUBMITTED', 'DRAFT', 'IN_REVIEW', None, '{}'))
def decide(r, v, action='APPROVED', status='APPROVED', by=cr, comment=None):
    e = fails(by, MOVE, status, v, 'IN_REVIEW')
    if e is None: by.execute(EVENT, (uuid.uuid4(), proj, r, v, action, 'IN_REVIEW', status, comment, '{}'))
    return e

syl = resource('SYLLABUS'); sv = draft(syl, {'modules': ['Circuits']}); submit(syl, sv)
check('the owner and reviewer are asked to review; not the author, not the viewer',
      unread(co) == 1 and unread(cr) == 1 and unread(ce) == 0 and unread(cv) == 0, (unread(co), unread(cr), unread(ce), unread(cv)))
n = notes_of(cr)[0]
check('the request names the sender, the work and the project, and links to it',
      n[3] == 'REVIEW_REQUESTED' and 'Editor asked you to review' in n[4] and n[6] == f'/projects/{proj}/syllabus' and n[2].startswith('Circuits'), n)
tasks = cr.execute(TASKS).fetchall()
check('the review is on the reviewer\'s task list', any(t[0] == 'REVIEW' and t[3] == syl for t in tasks), tasks)
check('…and not on the author\'s', not any(t[0] == 'REVIEW' and t[3] == syl for t in ce.execute(TASKS).fetchall()))
check('the editor can\'t decide', decide(syl, sv, by=ce) is not None)
check('the reviewer approves the syllabus', decide(syl, sv) is None)
check('the author is told who approved it', any(x[3] == 'APPROVED' and 'by Reviewer' in x[4] for x in notes_of(ce)))
check('the reviewer is not told about their own decision', not any(x[3] == 'APPROVED' for x in notes_of(cr)))

top = resource('TOPICS'); tv = draft(top, {'nodes': [{'id': 't1', 'title': "Ohm's law", 'children': [{'id': 't2', 'title': 'Resistance'},
                                               {'id': 't3', 'title': 'Left out', 'selected': False}]}, {'id': 't4', 'title': 'Kirchhoff'}]})
submit(top, tv)
check('changes can be asked for', decide(top, tv, 'CHANGES_REQUESTED', 'CHANGES_REQUESTED', comment='Split Kirchhoff into two') is None)
ch = [x for x in notes_of(ce) if x[3] == 'CHANGES_REQUESTED'][0]
check('the author sees the reviewer\'s comment', 'Split Kirchhoff into two' in (ch[5] or ''), ch)
check('…and the changes are on their task list', any(t[0] == 'CHANGES' and t[3] == top for t in ce.execute(TASKS).fetchall()))
tv2 = draft(top, {'nodes': [{'id': 't1', 'title': "Ohm's law", 'children': [{'id': 't2', 'title': 'Resistance'},
                  {'id': 't3', 'title': 'Left out', 'selected': False}]}, {'id': 't4', 'title': 'Kirchhoff'}]})
submit(top, tv2); decide(top, tv2)

def approved(kind, topic='t1', level=None, content=None):
    r = resource(kind, topic, level, f'{kind.title()} for Ohm'); v = draft(r, content or {'kind': kind}); submit(r, v)
    e = decide(r, v); assert e is None, (kind, e); return r, v
slides = resource('SLIDES', 't1', 'BEGINNER', "Ohm's law: beginner slides"); slv = draft(slides, {'slides': []}); submit(slides, slv)
check('a presentation can\'t be approved before its notes, QA and SEO (LX017)', decide(slides, slv) == 'LX017')
approved('NOTES', level='BEGINNER')
mcq, _ = approved('MCQ', content={'questions': [{'id': 'q1'}, {'id': 'q2'}, {'id': 'q3'}]})
approved('QA'); approved('SEO', content={'titles': [{'id': 'a', 'text': "Ohm's Law Explained"}], 'chosenTitleId': 'a'})
check('with them approved, the presentation is approved', decide(slides, slv) is None)

lesson = resource('LESSON_VIDEO', 't1', 'BEGINNER', "Ohm's law: beginner lesson video")
lv = uuid.uuid4(); lh = uuid.uuid4().hex * 2
ce.execute(INS_VER, (lv, lesson, 1, 'DRAFT', 'MANUAL', '{"kind":"LESSON"}', lh, None, None)); submit(lesson, lv)
check('a lesson video with no checked render can\'t be approved (LX019)', decide(lesson, lv) == 'LX019')
tl = uuid.uuid4(); ce.execute("insert into timelines (id, project_id, name, width, height, fps, document) values (%s,%s,'lesson',1920,1080,30,'{}')", (tl, proj))
job = uuid.uuid4(); media = uuid.uuid4()
ce.execute("insert into media_assets(id,project_id,kind,original_filename,content_type,size_bytes,checksum_sha256,storage_key) values (%s,%s,'VIDEO','v.mp4','video/mp4',1,'x',%s)", (media, proj, 'k/' + media.hex))
ce.execute("insert into render_jobs (id, timeline_id, timeline_name, project_id, status, settings, document) values (%s,%s,'Ohm lesson',%s,'RENDERING','{}','{}')", (job, tl, proj))
ce.execute(RECORD, (uuid.uuid4(), proj, lesson, lv, lh, job, tl))
active = ce.execute(ACTIVE).fetchall()
check('a running render is listed as active work', any(a[0] == 'RENDER' and a[7] == str(lesson) for a in active), active)
worker.execute("update render_jobs set status='COMPLETED', progress=1, output_media_id=%s, validation='{\"ok\":true}' where id=%s", (media, job))
r = [x for x in notes_of(ce) if x[3] == 'RENDER_DONE']
check('the person who rendered is told it\'s ready, with a link to the video', len(r) == 1 and r[0][6] == f'/videos/{lesson}', r)
check('the reviewer approves the video with its checked render', decide(lesson, lv) is None)
check('the owner is asked to publish it', any(t[0] == 'PUBLISH' and t[3] == lesson for t in co.execute(TASKS).fetchall()))
check('…the editor is not', not any(t[0] == 'PUBLISH' for t in ce.execute(TASKS).fetchall()))

# publication: the owner's post, authorised
post, target = uuid.uuid4(), uuid.uuid4()
acct = uuid.uuid4()
sysc.execute("insert into social_accounts(id,platform,external_id,display_name,status) values (%s,'YOUTUBE',%s,'Circuits channel','CONNECTED')", (acct, 'UC' + tag))
co.execute("insert into posts(id,title,media_id,status) values (%s,%s,%s,'SCHEDULED')", (post, "Ohm's Law Explained", media))
co.execute("insert into post_targets(id,post_id,account_id,platform,account_name,status,settings) values (%s,%s,%s,'YOUTUBE','Circuits channel','SCHEDULED',%s)",
           (target, post, acct, json.dumps({'title': "Ohm's Law Explained", 'privacy': 'public', 'madeForKids': False})))
check('nothing uploads before it is authorised', fails(worker, "update post_targets set status='UPLOADING' where id=%s", target) == 'LX020')
check('a public video needs the public confirmation', fails(co, AUTH, uuid.uuid4(), target, False, True, proj, lv, 'Authorised posting “Ohm” publicly.') == 'LX021')
co.execute(AUTH, (uuid.uuid4(), target, True, True, proj, lv, 'Authorised posting “Ohm’s Law Explained” to YouTube (Circuits channel), publicly.'))
co.execute(ADD_PUB, (uuid.uuid4(), proj, lesson, lv, 't1', "Ohm's Law Explained", target))
check('the video is no longer waiting to be published', not any(t[0] == 'PUBLISH' and t[3] == lesson for t in co.execute(TASKS).fetchall()))
worker.execute("update post_targets set status='UPLOADING' where id=%s", (target,))
worker.execute("update post_targets set status='PUBLISHED', remote_id='vidE10', remote_url='https://www.youtube.com/watch?v=vidE10', published_at=now() where id=%s", (target,))
p = [x for x in notes_of(co) if x[3] == 'PUBLISHED']
check('the owner is told it is on YouTube, with its address', len(p) == 1 and p[0][6] == 'https://www.youtube.com/watch?v=vidE10' and 'is on YouTube (Circuits channel)' in p[0][4], p)
cv.execute(ADD_SNAP, (uuid.uuid4(), proj, acct, 'BASIC', None, dt.date(2026, 11, 1), dt.date(2026, 11, 23),
                      json.dumps({'columns': ['video', 'views'], 'rows': [['vidE10', 42]]}), dt.datetime.now(dt.timezone.utc)))
check('an analytics snapshot of it is kept', sysc.execute("select count(*) from youtube_analytics_snapshots where project_id=%s", (proj,)).fetchone()[0] == 1)

# ---- jobs --------------------------------------------------------------------------------------------
j1 = uuid.uuid4()
ce.execute("insert into analysis_jobs(id,project_id,kind,target,input) values (%s,%s,'NOTES','t4:BEGINNER','{}')", (j1, proj))
worker.execute("update analysis_jobs set status='RUNNING' where id=%s", (j1,))
check('a running job is not a notification yet', not any(x[3].startswith('JOB') for x in notes_of(ce)))
worker.execute("update analysis_jobs set status='FAILED', error='The AI account has no credit', finished_at=now() where id=%s", (j1,))
f = [x for x in notes_of(ce) if x[3] == 'JOB_FAILED']
check('the person who started a job is told it failed, and why', len(f) == 1 and 'no credit' in f[0][5] and f[0][6] == f'/projects/{proj}/materials', f)
check('…and it is on their task list', any(t[0] == 'FAILED_JOB' and 'no credit' in t[5] for t in ce.execute(TASKS).fetchall()))
j2 = uuid.uuid4()
ce.execute("insert into analysis_jobs(id,project_id,kind,target,input) values (%s,%s,'NOTES','t4:BEGINNER','{}')", (j2, proj))
check('trying again takes it off the list', not any(t[0] == 'FAILED_JOB' for t in ce.execute(TASKS).fetchall()))
ce.execute("update analysis_jobs set status='CANCELLED' where id=%s", (j2,))
check('cancelling your own job tells nobody', len([x for x in notes_of(ce) if x[3].startswith('JOB')]) == 1)

# ---- the dashboard, as each person sees it -----------------------------------------------------------
c = counts(cv)
check('the viewer\'s dashboard counts this project\'s work',
      c[0] == 1 and c[1] == 1 and c[2] == 1 and c[3] == 3 and c[6] == 3 and c[12] == 1 and c[14] == 1 and c[15] == 1, c)
acts = cv.execute(ACTIVITY).fetchall()
check('recent decisions are listed with who made them', acts[0][1] in ('APPROVED', 'SUBMITTED') and any(a[1] == 'CHANGES_REQUESTED' and a[7] == 'Reviewer' for a in acts), acts[:3])

# ---- reading and clearing ---------------------------------------------------------------------------
before = unread(ce)
first = notes_of(ce)[0][0]
ce.execute(MARK, (first,))
check('marking one read', unread(ce) == before - 1)
check('someone else can\'t mark it', cr.execute(MARK, (first,)).rowcount == 0 and cr.execute("delete from notifications where id=%s", (first,)).rowcount == 0)
check('only the read time can be changed', fails(ce, "update notifications set title='x' where id=%s", first) == '42501')
check('nobody writes notifications directly', fails(ce, "insert into notifications(user_id,kind,title) values (%s,'APPROVED','x')", editor) == '42501')
ce.execute(MARK_ALL)
check('marking all read', unread(ce) == 0 and len(ce.execute(LIST, (True, 50)).fetchall()) == 0 and len(notes_of(ce)) > 0)
sysc.execute("update users set status='DEACTIVATED' where id=%s", (reviewer,))
r2 = resource('NOTES', 't4', 'BEGINNER', 'Kirchhoff notes'); v2 = draft(r2, {}); submit(r2, v2)
link = f'/notes/{r2}'
check('deactivated people get no notifications', sysc.execute("select count(*) from notifications where user_id=%s and link=%s", (reviewer, link)).fetchone()[0] == 0
      and sysc.execute("select count(*) from notifications where user_id=%s and link=%s", (owner, link)).fetchone()[0] == 1)

outsider_counts = counts(conn('user', mk('Outsider')))
check('an outsider\'s dashboard is empty', all(x == 0 for x in outsider_counts), outsider_counts)

# clean up
sysc.execute("delete from projects where id=%s", (proj,))
sysc.execute("set session_replication_role = replica")
sysc.execute("delete from posts where id=%s", (post,))
sysc.execute("delete from social_accounts where id=%s", (acct,))
sysc.execute("delete from media_assets where project_id is null and storage_key like 'k/%%'")
sysc.execute("delete from users where email like %s", (f'%-{tag}@t.lk',))
print(f"E10 (V18) whole workflow, notifications and dashboard: {ok} checks passed")
