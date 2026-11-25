"""E2 end to end on real PostgreSQL with the exact SQL in DocumentStore.java:
upload as a person, claim and save as the worker, search as members and an outsider."""
import _env
import psycopg, uuid, sys, re, json
DSN = _env.DSN
SRC = _env.SRC
_act = open(SRC+'access/ActorDataSource.java').read()
ACT_SQL = re.search(r'ACT_SQL = """(.*?)""";', _act, re.S).group(1).replace('?', '%s')
store = open(SRC+'documents/DocumentStore.java').read()
blocks = re.findall(r'"""(.*?)"""', store, re.S)
def block(*needles):
    hits = [b for b in blocks if all(n in b for n in needles)]
    assert len(hits) == 1, (needles, len(hits))
    return hits[0]
VCOLS = block('v.id, v.version_no')
def strs(*needles):  # one-line "..." statements
    hits = [m for m in re.findall(r'"((?:select|insert|update|delete)[^"]*)"', store) if all(n in m for n in needles)]
    assert len(hits) == 1, (needles, hits)
    return hits[0]
P = lambda s: s.replace('?', '%s')
CLAIM = P(block('UPDATE document_versions SET locked_until'))
INS_DOC = P(strs('insert into documents'))
INS_VER = P(block('insert into document_versions'))
INS_PAGE = P(strs('insert into document_pages'))
INS_CHUNK = P(strs('insert into document_chunks'))
SAVE = P(block("status = 'READY', error = null, page_count"))
REQUEUE = P(block("status = 'QUEUED', force_ocr"))
RETRY = P(block('make_interval'))
LIST_VERS = P("select " + VCOLS + block('where d.project_id = ? order by v.version_no desc'))
CAND_HEAD = block('with latest as')
def candidates(c, project, words, limit=300):
    sql = CAND_HEAD + ''.join(" and exists (select 1 from unnest(c.terms) t where t like ?)" for _ in words) + " order by d.updated_at desc, c.ordinal limit ?"
    return c.execute(P(sql), [project] + [w + '%' for w in words] + [limit]).fetchall()

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
def fails(c, sql, *p):
    try: c.execute(sql, p); return None
    except psycopg.Error as e: return e.sqlstate

sysc = conn()
admin = sysc.execute("select id from users where role='ADMIN' and status='ACTIVE' limit 1").fetchone()[0]
sysc.execute("update users set status='DEACTIVATED' where id <> %s and status <> 'DEACTIVATED'", (admin,))
tag = uuid.uuid4().hex[:6]
mk = lambda n: sysc.execute("insert into users(email,display_name,password_hash) values (%s,%s,'x') returning id", (f"{n}-{tag}@t.lk", n)).fetchone()[0]
lect, student, out = mk('Lecturer'), mk('Student'), mk('Outsider')
cl, cs, co = conn('user', lect), conn('user', student), conn('user', out)
proj = uuid.uuid4()
cl.execute("insert into projects(id,name,kind,module_code) values (%s,%s,'EDUCATION','CSC2041')", (proj, 'DBMS '+tag))
cl.execute("insert into project_members(project_id,user_id,role,added_by) values (%s,%s,'VIEWER',%s)", (proj, student, lect))

# ---- upload two documents as the lecturer (createDocument)
docs = {}
for name in ['lesson.pdf', 'lesson-scan.pdf']:
    d, v = uuid.uuid4(), uuid.uuid4()
    cl.execute(INS_DOC, (d, proj, name))
    cl.execute(INS_VER, (v, d, 1, name, 'application/pdf', 1000, uuid.uuid4().hex*2, f'documents/{v}.pdf'))
    docs[name] = (d, v)
check('viewer cannot upload', fails(cs, INS_DOC, uuid.uuid4(), proj, 'x') == '42501')

# ---- the worker claims both, and saves what the service read (as the uploader)
claimed = sysc.execute(CLAIM, (5,)).fetchall()
check('worker claims queued versions', {r[0] for r in claimed} >= {v for _, v in docs.values()}, claimed)
check('claimed versions are leased', sysc.execute(CLAIM, (5,)).fetchall() == [])
for name, (d, v) in docs.items():
    res = json.load(open(f'{_env.FIXTURES}/{name}.json')); ch = json.load(open(f'{_env.FIXTURES}/{name}.chunks.json'))['chunks']
    w = psycopg.connect(DSN, autocommit=True); w.execute(ACT_SQL, ('worker', str(lect), 'worker', 'worker', 'worker', None, None))
    with w.transaction():
        for p in res['pages']:
            w.execute(INS_PAGE, (v, p['page'], p['method'], p['confidence'], p['text']))
        for c in ch:
            w.execute(INS_CHUNK, (v, c['page'], c['ordinal'], c['text'], c['terms']))
        codes = '{' + ','.join('"%s"' % x for x in res['moduleCodes']) + '}'
        w.execute(SAVE, (res['pageCount'], res['pagesRead'], res['ocrPages'], 95.2 if res['ocrPages'] else None, res['language'], codes, res['title'], v))
    w.close()

rows = cs.execute(LIST_VERS, (proj,)).fetchall()
check('student sees both versions, read', len(rows) == 2 and all(r[5] == 'READY' for r in rows), rows)
scan = [r for r in rows if r[2] == 'lesson-scan.pdf'][0]
check('scan: 2 pages by OCR, module code found', scan[7] == 2 and scan[9] == 2 and scan[12] == ['CSC2041'], scan)

# ---- search as the app does it
def hits(c, words): return [(r[1], r[4]) for r in candidates(c, proj, words)]
check('Tamil word found on page 1 of both', sorted(hits(cs, ['முதன்மை'])) == [('lesson-scan.pdf', 1), ('lesson.pdf', 1)], hits(cs, ['முதன்மை']))
check('Tamil prefix finds suffixed word', len(hits(cs, ['பரிவர்த்தனை'])) == 2 and all(p == 2 for _, p in hits(cs, ['பரிவர்த்தனை'])))
check('two words must both appear', all(p == 2 for _, p in hits(cs, ['acid', 'transactions'])) and len(hits(cs, ['acid', 'transactions'])) == 2)
check('English prefix: normal → normalisation', len(hits(cs, ['normalis'])) == 2)
check('no false match', hits(cs, ['acid', 'முதன்மை']) == [])
check('outsider finds nothing', hits(co, ['முதன்மை']) == [])
check('a word in the middle does not match', hits(cs, ['ormal']) == [])

# ---- a newer version that fails: search keeps using the last good one
d, v1 = docs['lesson.pdf']
v2 = uuid.uuid4()
cl.execute(INS_VER, (v2, d, 2, 'lesson-v2.pdf', 'application/pdf', 10, 'c'*64, f'documents/{v2}.pdf'))
sysc.execute("update document_versions set status='FAILED', error='damaged' where id=%s", (v2,))
check('search falls back to the last read version', ('lesson.pdf', 1) in hits(cs, ['முதன்மை']))
check('viewer cannot requeue', cs.execute(REQUEUE, (True, v2)).rowcount == 0)
check('lecturer requeues a failed version with OCR', cl.execute(REQUEUE, (True, v2)).rowcount == 1)
check('requeue a queued version does nothing', cl.execute(REQUEUE, (False, v2)).rowcount == 0)
sysc.execute(RETRY, ('service down — trying again shortly', 60, v2))
check('retry waits before the next claim', v2 not in [r[0] for r in sysc.execute(CLAIM, (5,)).fetchall()])

check('outsider cannot read pages', co.execute("select count(*) from document_pages").fetchone()[0] == 0)
check('student reads OCR page text', 'தரவுத்தள' in cs.execute("select text from document_pages where version_id=%s and page_no=1", (docs['lesson-scan.pdf'][1],)).fetchone()[0])

sysc.execute("delete from projects where id=%s", (proj,))
sysc.execute("set session_replication_role = replica")
for u in (lect, student, out): sysc.execute("delete from users where id=%s", (u,))
print(f"E2 end to end: {ok} checks passed")
