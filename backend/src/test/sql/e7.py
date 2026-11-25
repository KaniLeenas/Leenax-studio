"""V15 and E7 on real PostgreSQL with the exact SQL in SlidesStore.java: decks per topic and level,
"no presentation before academic and SEO approval" (LX017), and the gate's lookups."""
import _env
import psycopg, uuid, sys, re, json, hashlib
DSN = _env.DSN
SRC = _env.SRC
ACT_SQL = re.search(r'ACT_SQL = """(.*?)""";', open(SRC+'access/ActorDataSource.java').read(), re.S).group(1).replace('?', '%s')
def text(path): return open(SRC+path).read()
def strs(path, *needles):
    hits = [m for m in re.findall(r'"((?:select|insert|update|delete)[^"]*)"', text(path)) if all(n in m for n in needles)]
    assert len(hits) == 1, (needles, hits); return hits[0].replace('?', '%s')
def joined(path, *needles):
    runs = [''.join(re.findall(r'"([^"]*)"', m)) for m in re.findall(r'"[^"]*"(?:\s*\+\s*"[^"]*")+', text(path))]
    hits = [r for r in runs if all(n in r for n in needles)]
    assert len(hits) == 1, (needles, hits); return hits[0].replace('?', '%s')

S = 'slides/SlidesStore.java'; RS = 'approvals/ReviewStore.java'; EX = 'notes/ExportService.java'
APPROVED = joined(S, "v.status = 'APPROVED'", 'is not distinct from'); CONTENT = strs(S, 'select content::text from resource_versions where id = ?')
DRAWN = strs(S, 'from note_figures'); IMAGE = strs(S, 'from media_assets'); GATE_CONTENT = joined(S, 'content_hash = ?')
FIG_EXPORT = [m for m in re.findall(r'"(select svg from note_figures[^"]*)"', text(EX))]
assert len(set(FIG_EXPORT)) == 1; FIG_EXPORT = FIG_EXPORT[0].replace('?', '%s')
INS_RES = strs(RS, 'insert into resources'); MOVE = strs(RS, 'update resource_versions set status = ?')
INS_VER = re.search(r'"""(\s*insert into resource_versions.*?)"""', text(RS), re.S).group(1).replace('?', '%s')

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
cl.execute("insert into projects(id,name,kind) values (%s,%s,'EDUCATION')", (proj, 'Slides '+tag))
cl.execute("insert into project_members(project_id,user_id,role,added_by) values (%s,%s,'REVIEWER',%s),(%s,%s,'VIEWER',%s)", (proj, rev, lect, proj, stu, lect))

# shape: a deck belongs to a topic and a notes level
check('a deck needs a topic', fails(cl, INS_RES, uuid.uuid4(), proj, 'SLIDES', 'x', None, 'BEGINNER') == '23514')
check('and a level', fails(cl, INS_RES, uuid.uuid4(), proj, 'SLIDES', 'x', 't1', None) == '23514')
deck = uuid.uuid4(); cl.execute(INS_RES, (deck, proj, 'SLIDES', 'Keys: beginner slides', 't1', 'BEGINNER'))
check('one deck per topic and level', fails(cl, INS_RES, uuid.uuid4(), proj, 'SLIDES', 'dup', 't1', 'BEGINNER') == '23505')
check('another level is another deck', fails(cl, INS_RES, uuid.uuid4(), proj, 'SLIDES', 'adv', 't1', 'ADVANCED') is None)
check('a viewer can\'t make a deck', fails(cs, INS_RES, uuid.uuid4(), proj, 'SLIDES', 'v', 't2', 'BEGINNER') == '42501')
check('a slides job is allowed', fails(cl, "insert into analysis_jobs(id,project_id,kind,target,input) values (%s,%s,'SLIDES','t1:BEGINNER','{}')", uuid.uuid4(), proj) is None)
check('one at a time per deck', fails(cl, "insert into analysis_jobs(id,project_id,kind,target,input) values (%s,%s,'SLIDES','t1:BEGINNER','{}')", uuid.uuid4(), proj) == '23505')
check('older kinds still work', fails(cl, "insert into analysis_jobs(id,project_id,kind,target,input) values (%s,%s,'SEO','t1','{}')", uuid.uuid4(), proj) is None)

# the deck's content and its review
src = "flowchart LR\n A-->B"
content = {"topicId": "t1", "level": "BEGINNER", "style": "LECTURE", "slides": [{"id": "a", "type": "TITLE", "layout": "TITLE", "title": "Keys"},
           {"id": "b", "type": "DIAGRAM", "layout": "DIAGRAM", "title": "Flow", "figure": {"kind": "MERMAID", "source": src}}]}
dv = uuid.uuid4(); cl.execute(INS_VER, (dv, deck, 1, 'DRAFT', 'AI', json.dumps(content), 'c'*64, None, None))
got = cs.execute(GATE_CONTENT, (deck, 'c'*64)).fetchone()
check('the gate finds the version by its content', got and json.loads(got[0])['slides'][1]['figure']['source'] == src)
cl.execute(MOVE, ('IN_REVIEW', dv, 'DRAFT'))
check('nothing approved yet: the deck can\'t be approved', fails(cr, MOVE, 'APPROVED', dv, 'IN_REVIEW') == 'LX017')
check('nor approved with edits', fails(cr, INS_VER, uuid.uuid4(), deck, 2, 'APPROVED', 'REVIEWER', json.dumps(content), 'd'*64, dv, None) == 'LX017')

def approved_res(kind, topic, level, title):
    r = uuid.uuid4(); cl.execute(INS_RES, (r, proj, kind, title, topic, level))
    v = uuid.uuid4(); cl.execute(INS_VER, (v, r, 1, 'DRAFT', 'MANUAL', '{}', uuid.uuid4().hex * 2, None, None))
    cl.execute(MOVE, ('IN_REVIEW', v, 'DRAFT')); e = fails(cr, MOVE, 'APPROVED', v, 'IN_REVIEW')
    assert e is None, (kind, e); return r, v

check('the approved notes are looked up by topic and level', cl.execute(APPROVED, (proj, 'NOTES', 't1', 'BEGINNER')).fetchone() is None)
nr, nv = approved_res('NOTES', 't1', 'ADVANCED', 'Keys advanced')
check('advanced notes don\'t count for the beginner deck', cl.execute(APPROVED, (proj, 'NOTES', 't1', 'BEGINNER')).fetchone() is None)
check('and don\'t unlock it', fails(cr, MOVE, 'APPROVED', dv, 'IN_REVIEW') == 'LX017')
nr, nv = approved_res('NOTES', 't1', 'BEGINNER', 'Keys beginner')
check('now the notes are found', cl.execute(APPROVED, (proj, 'NOTES', 't1', 'BEGINNER')).fetchone()[0] == nv)
check('a viewer sees the same', cs.execute(APPROVED, (proj, 'NOTES', 't1', 'BEGINNER')).fetchone()[0] == nv)
check('notes alone aren\'t enough', fails(cr, MOVE, 'APPROVED', dv, 'IN_REVIEW') == 'LX017')
qr, qv = approved_res('QA', 't1', None, 'QA')
check('the QA report is found without a level', cl.execute(APPROVED, (proj, 'QA', 't1', None)).fetchone()[0] == qv)
check('notes and QA without SEO aren\'t enough', fails(cr, MOVE, 'APPROVED', dv, 'IN_REVIEW') == 'LX017')
sr, sv = approved_res('SEO', 't2', None, 'SEO for another topic')
check('another topic\'s SEO report doesn\'t count', fails(cr, MOVE, 'APPROVED', dv, 'IN_REVIEW') == 'LX017')
sr, sv = approved_res('SEO', 't1', None, 'SEO')
check('with all three approved, the reviewer can approve', fails(cr, MOVE, 'APPROVED', dv, 'IN_REVIEW') is None)
check('and the deck reads as approved', cl.execute(APPROVED, (proj, 'SLIDES', 't1', 'BEGINNER')).fetchone()[0] == dv)
check('its content is read by id', json.loads(cs.execute(CONTENT, (dv,)).fetchone()[0])['style'] == 'LECTURE')

# diagrams: drawn once, found by kind and source hash (the gate and the export)
h = hashlib.sha256(src.strip().encode()).hexdigest()
check('not drawn yet', cl.execute(DRAWN, (proj, 'MERMAID', h)).fetchone()[0] == 0)
cl.execute("insert into note_figures (project_id, kind, source_hash, svg) values (%s, %s, %s, %s) on conflict do nothing", (proj, 'MERMAID', h, '<svg/>'))
check('drawn', cl.execute(DRAWN, (proj, 'MERMAID', h)).fetchone()[0] == 1)
check('a maths drawing with the same source is different', cl.execute(DRAWN, (proj, 'MATH', h)).fetchone()[0] == 0)
check('the export finds the drawing', cs.execute(FIG_EXPORT, (proj, 'MERMAID', h)).fetchone()[0] == '<svg/>')

# images: an image in this project's media; not audio, not unfiled, not another project's
def asset(kind, project):
    a = uuid.uuid4()
    sysc.execute("insert into media_assets(id,project_id,kind,original_filename,content_type,size_bytes,checksum_sha256,storage_key) values (%s,%s,%s,'f','image/png',1,'x',%s)",
                 (a, project, kind, 'k/' + a.hex)); return a
other = uuid.uuid4(); sysc.execute("insert into projects(id,name,kind,owner_id) values (%s,%s,'EDUCATION',%s)", (other, 'Other '+tag, admin)) if 'owner_id' in [r[0] for r in sysc.execute("select column_name from information_schema.columns where table_name='projects'")] else sysc.execute("insert into projects(id,name,kind) values (%s,%s,'EDUCATION')", (other, 'Other '+tag))
mine, shared, sound, theirs = asset('IMAGE', proj), asset('IMAGE', None), asset('AUDIO', proj), asset('IMAGE', other)
check('this project\'s image is fine', cl.execute(IMAGE, (mine, proj)).fetchone()[0] == 1)
check('an unfiled image isn\'t used, even by its owner', sysc.execute("update media_assets set created_by=%s where id=%s", (lect, shared)) and cl.execute(IMAGE, (shared, proj)).fetchone()[0] == 0)
check('a reviewer sees the project\'s image too', cr.execute(IMAGE, (mine, proj)).fetchone()[0] == 1)
check('audio isn\'t an image', cl.execute(IMAGE, (sound, proj)).fetchone()[0] == 0)
check('another project\'s image isn\'t used', sysc.execute(IMAGE, (theirs, proj)).fetchone()[0] == 0)

for a in (mine, shared, sound, theirs): sysc.execute("delete from media_assets where id=%s", (a,))
for p in (proj, other): sysc.execute("delete from projects where id=%s", (p,))
sysc.execute("set session_replication_role = replica")
for u in (lect, rev, stu): sysc.execute("delete from users where id=%s", (u,))
print(f"E7 (V15) end to end: {ok} checks passed")
