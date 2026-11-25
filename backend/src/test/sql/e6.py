"""V14 and E6 on real PostgreSQL with the exact SQL in SeoStore.java: the shared YouTube quota,
the 7-day cache, Trends imports kept as imported, research kept as found, and SEO reports."""
import _env
import psycopg, uuid, sys, re, json, datetime
DSN = _env.DSN
SRC = _env.SRC
ACT_SQL = re.search(r'ACT_SQL = """(.*?)""";', open(SRC+'access/ActorDataSource.java').read(), re.S).group(1).replace('?', '%s')
def text(path): return open(SRC+path).read()
def const(path, name): return re.search(name + r' = """(.*?)""";', text(path), re.S).group(1).replace('?', '%s')
def strs(path, *needles):
    hits = [m for m in re.findall(r'"((?:select|insert|update|delete)[^"]*)"', text(path)) if all(n in m for n in needles)]
    assert len(hits) == 1, (needles, hits); return hits[0].replace('?', '%s')
def joined(path, *needles):
    runs = [''.join(re.findall(r'"([^"]*)"', m)) for m in re.findall(r'"[^"]*"(?:\s*\+\s*"[^"]*")+', text(path))]
    hits = [r for r in runs if all(n in r for n in needles)]
    assert len(hits) == 1, (needles, hits); return hits[0].replace('?', '%s')
def after(path, const_name, *needles):
    hits = [''.join(re.findall(r'"([^"]*)"', m)) for m in re.findall(re.escape(const_name) + r'((?:\s*\+\s*"[^"]*")+)', text(path))]
    hits = [h for h in hits if all(n in h for n in needles)]
    assert len(hits) == 1, (needles, hits); return const(path, const_name) + hits[0].replace('?', '%s')

S = 'seo/SeoStore.java'; RS = 'approvals/ReviewStore.java'
USED = strs(S, 'leenax_seo_quota_used'); CALL = strs(S, 'insert into seo_api_calls')
CACHED = joined(S, 'from seo_search_cache'); CACHE = joined(S, 'insert into seo_search_cache')
INS_TRENDS = joined(S, 'insert into seo_trends')
assert '" where t.project_id = ?" + (activeOnly ? " and t.status = \'ACTIVE\'" : "") + " order by t.created_at desc"' in text(S)
TRENDS = const(S, 'TRENDS_SQL') + " where t.project_id = %s order by t.created_at desc"
TRENDS_ACTIVE = const(S, 'TRENDS_SQL') + " where t.project_id = %s and t.status = 'ACTIVE' order by t.created_at desc"
TRENDS_FILE = after(S, 'TRENDS_SQL', 't.id = ?'); REMOVE = strs(S, "set status = 'REMOVED'"); PROJ_TRENDS = strs(S, 'select project_id from seo_trends')
INS_RESEARCH = strs(S, 'insert into seo_research'); RESEARCH = after(S, 'RESEARCH_SQL', 'r.id = ?'); LATEST = joined(S, 'distinct on (r.topic_id)')
GATE_CONTENT = joined(S, 'content_hash = ?', 'resource_versions'); ACADEMIC = joined(S, "r.kind = 'QA'")
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
lect, other, stu = mk('Lecturer'), mk('Other'), mk('Student')
cl, co, cs = conn('user', lect), conn('user', other), conn('user', stu)
p1, p2 = uuid.uuid4(), uuid.uuid4()
cl.execute("insert into projects(id,name,kind) values (%s,%s,'EDUCATION')", (p1, 'SEO '+tag))
co.execute("insert into projects(id,name,kind) values (%s,%s,'EDUCATION')", (p2, 'SEO other '+tag))
cl.execute("insert into project_members(project_id,user_id,role,added_by) values (%s,%s,'VIEWER',%s)", (p1, stu, lect))
day = datetime.date(2031, 1, 7)  # a day no other test uses

# the quota is shared: another project's searches count, though the lecturer can't see them
w1, w2 = conn('worker', lect), conn('worker', other)
w2.execute(CALL, (uuid.uuid4(), p2, day, 'search.list', 100, 'sql joins', True))
w2.execute(CALL, (uuid.uuid4(), p2, day, 'videos.list', 1, None, True))
w1.execute(CALL, (uuid.uuid4(), p1, day, 'search.list', 100, 'third normal form', True))
w1.execute(CALL, (uuid.uuid4(), p1, day, 'search.list', 100, 'bcnf', False))  # a failed call still costs quota
check('the lecturer can\'t see the other project\'s calls', cl.execute("select count(*) from seo_api_calls where quota_day=%s", (day,)).fetchone()[0] == 2)
check('but the day\'s total counts every project', cl.execute(USED, (day,)).fetchone() == (3, 301))
check('a viewer sees the same total', cs.execute(USED, (day,)).fetchone() == (3, 301))
check('another day is separate', cl.execute(USED, (day + datetime.timedelta(days=1),)).fetchone() == (0, 0))
check('a viewer can\'t record a call', fails(cs, CALL, uuid.uuid4(), p1, day, 'search.list', 100, 'x', True) == '42501')
check('only search.list and videos.list are recorded', fails(w1, CALL, uuid.uuid4(), p1, day, 'activities.list', 1, None, True) == '23514')
check('the ledger can\'t be edited', cl.execute("update seo_api_calls set units=0 where project_id=%s", (p1,)).rowcount == 0)

# the cache: newest within 7 days, per project
now = datetime.datetime(2031, 1, 7, 12, tzinfo=datetime.timezone.utc)
key = 'en|LK|third normal form'
w1.execute(CACHE, (uuid.uuid4(), p1, key, 'third normal form', 'en', 'LK', json.dumps({"videos": [{"id": "old"}]}), now - datetime.timedelta(days=9)))
check('a 9-day-old result is too old', w1.execute(CACHED, (p1, key, now - datetime.timedelta(days=7))).fetchone() is None)
w1.execute(CACHE, (uuid.uuid4(), p1, key, 'third normal form', 'en', 'LK', json.dumps({"videos": [{"id": "v1"}]}), now - datetime.timedelta(days=2)))
hit = w1.execute(CACHED, (p1, key, now - datetime.timedelta(days=7))).fetchone()
check('a 2-day-old result is used', hit and hit[3]['videos'][0]['id'] == 'v1' if isinstance(hit[3], dict) else json.loads(hit[3])['videos'][0]['id'] == 'v1')
check('another project can\'t use it', co.execute(CACHED, (p1, key, now - datetime.timedelta(days=7))).fetchone() is None)

# Trends imports: kept as imported, removable from new research
t1 = uuid.uuid4()
check('the lecturer imports a Trends file', fails(cl, INS_TRENDS, t1, p1, 'multiTimeline.csv', 'Sri Lanka', 'WEEK', datetime.date(2025, 11, 23),
                                                   datetime.date(2026, 11, 22), json.dumps(["third normal form"]), json.dumps({"third normal form": [12, 40]})) is None)
check('a viewer can\'t', fails(cs, INS_TRENDS, uuid.uuid4(), p1, 'x.csv', None, 'WEEK', None, None, '[]', '{}') == '42501')
check('a viewer can read it', cs.execute(TRENDS_FILE, (t1,)).fetchone()[1] == 'multiTimeline.csv')
check('its numbers can\'t be changed', fails(cl, "update seo_trends set series='{}' where id=%s", t1) == 'LX016')
check('the project is found for permission checks', cs.execute(PROJ_TRENDS, (t1,)).fetchone()[0] == p1)
check('it can be removed from new research', cl.execute(REMOVE, (t1,)).rowcount == 1)
check('removing twice does nothing', cl.execute(REMOVE, (t1,)).rowcount == 0)
check('the active list leaves it out', [r[0] for r in cs.execute(TRENDS_ACTIVE, (p1,)).fetchall()] == [])
check('the full list keeps it', [r[0] for r in cs.execute(TRENDS, (p1,)).fetchall()] == [t1])

# research: kept as found
res = uuid.uuid4()
data = {"topicId": "t5", "keywords": [{"text": "3nf", "score": {"value": 0.4, "estimated": True}}]}
check('the worker saves research', fails(w1, INS_RESEARCH, res, p1, 't5', None, 'PARTIAL', json.dumps(data)) is None)
row = cs.execute(RESEARCH, (res,)).fetchone()
check('a viewer reads it', row[2] == 't5' and row[3] == 'PARTIAL')
check('research can\'t be edited', cl.execute("update seo_research set data='{}' where id=%s", (res,)).rowcount == 0)
check('or deleted', cl.execute("delete from seo_research where id=%s", (res,)).rowcount == 0)
check('the other project can\'t read it', co.execute(RESEARCH, (res,)).fetchone() is None)
res2 = uuid.uuid4(); w1.execute(INS_RESEARCH, (res2, p1, 't5', None, 'COMPLETE', json.dumps(data)))
check('the newest research per topic is listed', [(r[0], r[3]) for r in cs.execute(LATEST, (p1,)).fetchall()] == [(res2, 'COMPLETE')])

# an SEO report per topic, and the review check's lookups
check('an SEO report needs a topic', fails(cl, INS_RES, uuid.uuid4(), p1, 'SEO', 'x', None, None) == '23514')
check('and has no level', fails(cl, INS_RES, uuid.uuid4(), p1, 'SEO', 'x', 't5', 'EXAM') == '23514')
rep = uuid.uuid4(); cl.execute(INS_RES, (rep, p1, 'SEO', '1NF to 3NF: SEO report', 't5', None))
check('one SEO report per topic', fails(cl, INS_RES, uuid.uuid4(), p1, 'SEO', 'dup', 't5', None) == '23505')
content = {"topicId": "t5", "researchId": str(res2), "titles": []}
v1 = uuid.uuid4(); cl.execute(INS_VER, (v1, rep, 1, 'DRAFT', 'AI', json.dumps(content), 'e'*64, None, None))
got = cs.execute(GATE_CONTENT, (rep, 'e'*64)).fetchone()
check('the check finds the version by its content', got and json.loads(got[0])['researchId'] == str(res2))
check('the academic check counts approved QA reports for the topic', cs.execute(ACADEMIC, (p1, 't5')).fetchone()[0] == 0)
qa = uuid.uuid4(); cl.execute(INS_RES, (qa, p1, 'QA', 'QA', 't5', None))
qv = uuid.uuid4(); cl.execute(INS_VER, (qv, qa, 1, 'DRAFT', 'MANUAL', '{}', 'f'*64, None, None))
sysc.execute("set session_replication_role = replica"); sysc.execute("update resource_versions set status='APPROVED' where id=%s", (qv,)); sysc.execute("set session_replication_role = origin")
check('and sees one once approved', cs.execute(ACADEMIC, (p1, 't5')).fetchone()[0] == 1)
check('an SEO job is allowed', fails(cl, "insert into analysis_jobs(id,project_id,kind,target,input) values (%s,%s,'SEO','t5','{}')", uuid.uuid4(), p1) is None)

for p in (p1, p2): sysc.execute("delete from projects where id=%s", (p,))
sysc.execute("set session_replication_role = replica")
for u in (lect, other, stu): sysc.execute("delete from users where id=%s", (u,))
print(f"E6 (V14) end to end: {ok} checks passed")
