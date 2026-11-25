import _env
import psycopg, uuid
c=psycopg.connect(_env.DSN, autocommit=True)
def q(sql,*p):
    with c.cursor() as cur:
        cur.execute(sql,p)
        try: return cur.fetchall()
        except psycopg.ProgrammingError: return None
ok=0
def check(n,cond,got=None):
    global ok
    if not cond: raise SystemExit(f"FAIL {n} {got}")
    ok+=1
proj=uuid.uuid4(); other=uuid.uuid4()
q("insert into projects(id,name) values(%s,'P'),(%s,'Q')",proj,other)
m=uuid.uuid4(); q("insert into media_assets(id,kind,source,original_filename,content_type,size_bytes,checksum_sha256,storage_key) values(%s,'IMAGE','UPLOAD','a.png','image/png',1,'x','k8')",m)
s1=uuid.uuid4(); q("insert into scripts(id,project_id,title,language,video_type,body,sections) values(%s,%s,'T','TA','YOUTUBE_SHORT','b','{\"scenes\":[]}')",s1,proj)
s2=uuid.uuid4(); q("insert into scripts(id,project_id,parent_id,title,language,video_type,version_no) values(%s,%s,%s,'T2','TA','YOUTUBE_SHORT',2)",s2,proj,s1)
check('script versions', q("select count(*) from scripts where parent_id=%s",s1)[0][0]==1)
g=uuid.uuid4(); pc=uuid.uuid4(); oc=uuid.uuid4()
q("insert into storyboard_characters(id,project_id,name) values(%s,null,'Global'),(%s,%s,'Mine'),(%s,%s,'Other')",g,pc,proj,oc,other)
names=[r[0] for r in q("select c.name from storyboard_characters c where (c.project_id = %s or c.project_id is null) and c.id in (%s,%s,%s) order by c.name",proj,g,pc,oc)]
check('characters for project = own + shared', names==['Global','Mine'], names)
q("insert into storyboard_references(character_id,media_id,position) values(%s,%s,0)",pc,m)
b=uuid.uuid4(); q("insert into storyboard_projects(id,project_id,script_id,title,aspect_ratio,language) values(%s,%s,%s,'B','PORTRAIT','TA')",b,proj,s1)
sc=uuid.uuid4(); q("insert into storyboard_scenes(id,storyboard_id,position,character_ids,dialogue,duration_seconds) values(%s,%s,0,%s,'[{\"character\":\"Mine\",\"line\":\"hi\"}]',7.5)",sc,b,f'["{pc}"]')
q("insert into storyboard_references(scene_id,media_id,position) values(%s,%s,0)",sc,m)
try:
    q("insert into storyboard_references(character_id,scene_id,media_id) values(%s,%s,%s)",pc,sc,m); check('ref check',False)
except psycopg.errors.CheckViolation: check('a reference belongs to a character OR a scene',True)
try:
    q("insert into storyboard_projects(title,aspect_ratio,language) values('x','WIDE','EN')"); check('aspect',False)
except psycopg.errors.CheckViolation: check('aspect ratio constrained',True)
q("delete from storyboard_projects where id=%s",b)
check('scenes cascade with storyboard', q("select count(*) from storyboard_scenes where id=%s",sc)[0][0]==0)
check('scene references cascade', q("select count(*) from storyboard_references where scene_id=%s",sc)[0][0]==0)
q("delete from scripts where id=%s",s1)
check('child version survives parent delete', q("select parent_id from scripts where id=%s",s2)[0][0] is None)
q("delete from media_assets where id=%s",m)
check('character refs go with the image', q("select count(*) from storyboard_references where character_id=%s",pc)[0][0]==0)
q("delete from projects where id=%s",proj)
check('characters survive project delete as shared-less', q("select project_id from storyboard_characters where id=%s",pc)[0][0] is None)
print(ok,'V8 SQL checks passed')
