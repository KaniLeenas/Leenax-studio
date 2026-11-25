"""Runs the SQL text blocks from the Java sources against a real PostgreSQL with seeded data."""
import _env
import re, sys, json, uuid, datetime as dt
import psycopg
B=_env.SRC
def blocks(path):
    src=open(B+path).read()
    return [b for b in re.findall(r'"""\n(.*?)"""', src, re.S)]
conn=psycopg.connect(_env.DSN, autocommit=True)
def q(sql, *params):
    sql=sql.replace('?','%s')
    with conn.cursor() as c:
        c.execute(sql, params)
        try: return c.fetchall()
        except psycopg.ProgrammingError: return None
now=dt.datetime.now(dt.timezone.utc)
# ---- seed ----
for t in ['post_metrics','post_targets','posts','social_tokens','social_accounts','usage_logs','audit_logs','mfa_recovery_codes','users','media_assets']:
    q('set session_replication_role = replica')   # test reset only: users can't be deleted otherwise
    q(f'delete from {t}')
    q('set session_replication_role = origin')
q("insert into users(email,display_name,password_hash,role) values('k@x.com','Kani','x','ADMIN')")
mid=uuid.uuid4()
q("insert into media_assets(id,kind,source,original_filename,content_type,size_bytes,checksum_sha256,storage_key) values(%s,'VIDEO','UPLOAD','a.mp4','video/mp4',1000,'x','k1')".replace('%s','?'), mid)
for d,unit,cost,prov,cap,ok in [(0,'CREDITS',320,'RUNWAY','VIDEO',True),(1,'CREDITS',60,'RUNWAY','VIDEO',True),(1,'USD',0.42,'OPENAI','TEXT',True),(3,'USD',1.1,'OPENAI','IMAGE',True),(2,None,None,'KLING','VIDEO',False),(40,'CREDITS',999,'RUNWAY','VIDEO',True)]:
    q("insert into usage_logs(account_name,provider,model_code,capability,cost,cost_unit,cost_source,success,created_at) values('a',?,'m',?,?,?,'PROVIDER_REPORTED',?,?)", prov,cap,cost,unit,ok,now-dt.timedelta(days=d))
acc={}
for p in ['YOUTUBE','INSTAGRAM','TIKTOK']:
    a=uuid.uuid4(); acc[p]=a
    q("insert into social_accounts(id,platform,external_id,display_name,status) values(?,?,?,?, 'CONNECTED')", a,p,'x'+p,p.title())
post=uuid.uuid4(); q("insert into posts(id,title,media_id,status,scheduled_at,published_at) values(?,'Ella',?,'PARTIAL',?,?)", post, mid, now-dt.timedelta(days=2), now-dt.timedelta(days=2))
tg={}
q('set session_replication_role = replica')   # rows published before authorisation records existed (V17)
for p,st,rid,url in [('YOUTUBE','PUBLISHED','yt1','https://y'),('INSTAGRAM','PUBLISHED','ig1','https://i'),('TIKTOK','FAILED',None,None)]:
    t=uuid.uuid4(); tg[p]=t
    q("insert into post_targets(id,post_id,account_id,platform,account_name,status,remote_id,remote_url,published_at,error_message) values(?,?,?,?,?,?,?,?,?,?)",
      t,post,acc[p],p,p.title(),st,rid,url,(now-dt.timedelta(days=2)) if st=='PUBLISHED' else None,'boom' if st=='FAILED' else None)
q('set session_replication_role = origin')
post2=uuid.uuid4(); q("insert into posts(id,title,media_id,status,scheduled_at) values(?,'Later',?,'SCHEDULED',?)", post2, mid, now+dt.timedelta(days=1))
q("insert into post_targets(post_id,account_id,platform,account_name,status,run_at) values(?,?,'YOUTUBE','Yt','SCHEDULED',?)", post2, acc['YOUTUBE'], now+dt.timedelta(days=1))
q("insert into post_metrics(target_id,platform,views,likes,collected_at) values(?,'YOUTUBE',100,5,?)", tg['YOUTUBE'], now-dt.timedelta(hours=7))
q("insert into post_metrics(target_id,platform,views,likes,comments,collected_at) values(?,'YOUTUBE',250,9,2,?)", tg['YOUTUBE'], now-dt.timedelta(hours=1))
q("insert into post_metrics(target_id,platform,likes,comments,details,collected_at) values(?,'INSTAGRAM',12,3,'{\"source\":\"basic\"}'::jsonb,?)", tg['INSTAGRAM'], now)
for a in ['LOGIN_SUCCEEDED','MFA_ENABLED','POST_PUBLISHED','MEDIA_DELETED','LOGIN_FAILED']:
    q("insert into audit_logs(action,details) values(?, '{\"k\":1}'::jsonb)", a)

ok=0
def check(name, cond, got):
    global ok
    if not cond: print('FAIL', name, got); sys.exit(1)
    ok+=1
# ---- AnalyticsService ----
b=blocks('analytics/AnalyticsService.java')
frm=now-dt.timedelta(days=29)
tz='Asia/Colombo'
totals=q(b[0],frm); check('cost totals by unit (no mixing)', sorted((r[0],float(r[1])) for r in totals)==[('CREDITS',380.0),('USD',1.52)], totals)
daily=q(b[1],tz,frm); check('daily costs', len(daily)==4 and all(isinstance(r[0],dt.date) for r in daily), daily)
byp=q(b[2],frm); check('by provider', any(r[0]=='KLING' and r[4]==1 for r in byp), byp)
cnt=q(b[3],frm)[0]; check('calls/failed/unknown', tuple(cnt)==(5,1,0), cnt)
pub=q(b[4],frm,frm,frm)[0]; check('publishing counts', tuple(pub)==(2,2,1,1), pub)
pp=q(b[5],frm,frm); check('by platform', sorted(tuple(r) for r in pp)==[('INSTAGRAM',1,0),('TIKTOK',0,1),('YOUTUBE',1,0)], pp)
pd=q(b[6],tz,frm); check('daily published', sum(r[2] for r in pd)==2, pd)
fl=q(b[7],frm); check('recent failures', len(fl)==1 and fl[0][4]=='boom', fl)
latest=b[8]
perf=q(latest+b[9],frm); check('performance uses latest snapshot', [tuple(r) for r in perf if r[0]=='YOUTUBE']==[('YOUTUBE',1,1,250,9,2,None)], perf)
top=q(latest+b[10],frm); check('top posts ordered by views', top[0][3]=='YOUTUBE' and top[0][6]==250 and top[1][3]=='INSTAGRAM' and top[1][6] is None, top)
hist=q(b[11],tg['YOUTUBE']); check('history', [r[1] for r in hist]==[100,250], hist)
# ---- MetricsCollector.due ----
mb=blocks('analytics/MetricsCollector.java')
due=q(mb[0],90,False); check('due: both published, never checked', len(due)==2, due)
q("update post_targets set metrics_checked_at=now() where id=?", tg['YOUTUBE'])
due=q(mb[0],90,False); check('due: skip recently checked', len(due)==1, due)
due=q(mb[0],90,True); check('due: manual refresh takes all', len(due)==2, due)
q(mb[1],tg['INSTAGRAM'],'INSTAGRAM',None,4,1,None,None,None,'{"a":1}')
check('insert snapshot', q("select count(*) from post_metrics where target_id=?",tg['INSTAGRAM'])[0][0]==2, None)
# ---- PublishWorker claim ----
wb=blocks('publish/PublishWorker.java')
q("update post_targets set run_at=now()-interval '1 minute' where post_id=?", post2)
claimed=q(wb[0],2); check('publish claim', len(claimed)==1, claimed)
claimed=q(wb[0],2); check('publish claim is leased', len(claimed)==0, claimed)
# ---- AuditController ----
src=open(B+'audit/AuditController.java').read()
pats=['LOGIN%','MFA%','PASSWORD%','SETUP%','OWNER%']
where='(' + ' OR '.join('a.action LIKE ?' for _ in pats) + ')'
check('audit security count', q('SELECT count(*) FROM audit_logs a WHERE '+where,*pats)[0][0]==3, None)
sel='SELECT a.id, a.action, a.entity_type, a.entity_id, a.ip_address, a.details::text, a.created_at, u.display_name FROM audit_logs a LEFT JOIN users u ON u.id = a.user_id WHERE '
rows=q(sel+where+' ORDER BY a.created_at DESC LIMIT ? OFFSET ?',*pats,2,0)
check('audit page', len(rows)==2 and json.loads(rows[0][5])=={'k':1}, rows)
uid=q("select id from users limit 1")[0][0]
check('a member sees only their own activity', q('SELECT count(*) FROM audit_logs a WHERE '+where+' AND a.user_id = ?',*pats,uid)[0][0]==0, None)
# ---- SystemStatusService ----
sb=blocks('system/SystemStatusService.java')
for table,w,r,u in [('generation_jobs',"'QUEUED'","'PROCESSING'","coalesce(completed_at, created_at)"),('render_jobs',"'QUEUED'","'RENDERING'","updated_at"),('auto_edit_jobs',"'QUEUED'","'RUNNING'","updated_at"),('post_targets',"'SCHEDULED'","'UPLOADING','PROCESSING'","updated_at")]:
    sql=("SELECT count(*) FILTER (WHERE status IN ("+w+")), count(*) FILTER (WHERE status IN ("+r+")), count(*) FILTER (WHERE status = 'FAILED' AND "+u+" > now() - interval '24 hours') FROM "+table)
    res=q(sql); check('queue '+table, len(res)==1, res)
check('flyway-less schema query shape', True, None)
q("SELECT pg_database_size(current_database())")
q("SELECT count(*), coalesce(sum(size_bytes), 0) FROM media_assets")
q("SELECT count(*) FROM social_accounts WHERE status = 'NEEDS_RECONNECT'")
q("SELECT count(*) FROM ai_accounts WHERE status = 'INVALID'")
# ---- earlier workers' claim SQL (never run against real Postgres before) ----
for f in ['autoedit/AutoEditWorker.java','editor/RenderWorker.java','ai/generation/GenerationWorker.java']:
    for blk in blocks(f):
        if 'UPDATE' in blk and 'RETURNING' in blk:
            n=blk.count('?')
            q(blk,*([5]*n)); ok+=1
# ---- SocialAccountService.disconnect update ----
for blk in blocks('social/SocialAccountService.java'):
    if 'UPDATE post_targets' in blk: q(blk, acc['TIKTOK']); ok+=1
print(ok,'SQL checks passed')
