"""Read-only recovery inspection smoke. Requires Docker and JAVA_HOME; creates isolated DB/NAS."""
import hashlib,json,os,pathlib,subprocess,sys,tempfile,time,uuid
clone=pathlib.Path(sys.argv[1]); jar=clone/'backend/processor/console/build/libs/console.jar'
java=str(pathlib.Path(os.environ['JAVA_HOME'])/'bin/java')
suffix=uuid.uuid4().hex[:10]; db='tsvideos-inspect-db-'+suffix; nas='tsvideos-inspect-nas-'+suffix
def docker(*args,**kwargs):
 return subprocess.run(['docker',*args],check=True,capture_output=True,text=True,**kwargs).stdout.strip()
def sql(s): return docker('exec','-i',db,'mariadb','-uroot','-pissue75','--batch','--skip-column-names','test',input=s)
def q(s): return "'"+str(s).replace('\\','\\\\').replace("'","''")+"'"
def sha(b): return hashlib.sha256(b).hexdigest()
try:
 docker('run','--rm','-d','--name',db,'-e','MARIADB_ROOT_PASSWORD=issue75','-e','MARIADB_DATABASE=test','-p','127.0.0.1::3306','mariadb:11.4')
 for _ in range(120):
  try: sql('SELECT 1'); break
  except subprocess.CalledProcessError: time.sleep(.25)
 sql((clone/'backend/core/src/test/resources/ddl/01_create_database.sql').read_text(encoding='utf-8-sig'))
 docker('run','--rm','-d','--name',nas,'-e','AVAHI_DISABLE=1','-e','WSDD2_DISABLE=1','-e','NETBIOS_DISABLE=1','-e','ACCOUNT_pinfort=password','-e','UID_pinfort=1000','-e','SAMBA_VOLUME_CONFIG_alice=[alice]; path=/shares/alice; valid users = pinfort; guest ok = no; read only = no','-e','SAMBA_VOLUME_CONFIG_bob=[bob]; path=/shares/bob; valid users = pinfort; guest ok = no; read only = no','--tmpfs','/shares:rw','-p','127.0.0.1::445','ghcr.io/servercontainers/samba:latest')
 docker('exec',nas,'sh','-c','mkdir -p /shares/alice /shares/bob && chmod 777 /shares /shares/alice /shares/bob')
 time.sleep(2)
 dbport=docker('port',db,'3306/tcp').split(':')[-1]; nasport=docker('port',nas,'445/tcp').split(':')[-1]
 env=dict(os.environ,DATABASE_CONNECTION=f'jdbc:mariadb://127.0.0.1:{dbport}/test',DATABASE_USER_NAME='root',DATABASE_PASSWORD='issue75',VIDEO_STORE_NAS_URL=f'smb://127.0.0.1:{nasport}/alice',ORIGINAL_STORE_NAS_URL=f'smb://127.0.0.1:{nasport}/bob',VIDEO_STORE_NAS_USERNAME='pinfort',VIDEO_STORE_NAS_PASSWORD='password',ORIGINAL_STORE_NAS_USERNAME='pinfort',ORIGINAL_STORE_NAS_PASSWORD='password')
 with tempfile.TemporaryDirectory(prefix='issue63-real-smoke-') as tmp:
  root=pathlib.Path(tmp); splitdir=root/'tssplitter'; splitdir.mkdir(); (splitdir/'succeeded').mkdir(); (splitdir/'encoded').mkdir()
  raw=root/'raw.m2ts'; raw.write_bytes(b'original recording')
  split=splitdir/'main.m2ts'; split.write_bytes(b'main split')
  moved=splitdir/'succeeded/main.m2ts'; moved.write_bytes(b'main split')
  one=splitdir/'encoded/一.mp4'; one.write_bytes(b'encoded one')
  two=splitdir/'encoded/two.mp4'; two.write_bytes(b'encoded two')
  archive=splitdir/'main.m2ts.gz'; archive.write_bytes(b'verified archive')
  def evidence(p): return dict(path=str(p),size=p.stat().st_size,modified=p.stat().st_mtime_ns//1000000,sha256=sha(p.read_bytes()),rowId=None)
  rawmtime=raw.stat().st_mtime_ns//1000000; identity=sha(f'{raw}\0{raw.stat().st_size}\0{rawmtime}'.encode())
  e1,e2,ea=evidence(one),evidence(two),evidence(archive)
  manifest=dict(jobId=1,itemId=9,splitId=1,inPath=str(moved),outputs=[dict(file=e1,target='x/show/one.mp4',mime='video/mp4',encoding=None),dict(file=e2,target='x/show/two.mp4',mime='video/mp4',encoding=None)],cleanupFiles=[evidence(split),evidence(moved)])
  sql(f"INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) VALUES(1,{q(raw)},0,{raw.stat().st_size},'2026-01-01','1','raw','channel',60,'SPLITTED'); INSERT INTO program(id,name,executed_file_id,status) VALUES(1,'raw.m2ts',1,'ERROR'); INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(1,1,{q(split)},{split.stat().st_size},60,'COMPRESS_SAVED'); INSERT INTO processing_job(id,identity_key,input_path,input_size,modified_millis,state,executed_file_id) VALUES(1,{q(identity)},{q(raw)},{raw.stat().st_size},{rawmtime},'SUBMITTED',1); INSERT INTO processing_attempt(id,job_id,attempt_number,kind,outcome,ended_at) VALUES(1,1,1,'CALLBACK','FAILED',UTC_TIMESTAMP(6)); INSERT INTO processing_history_identity(job_id,identity_key,input_path,input_size,modified_millis,last_attempt) VALUES(1,{q(identity)},{q(raw)},{raw.stat().st_size},{rawmtime},1); INSERT INTO processing_callback_input(job_id,manifest) VALUES(1,{q(json.dumps(manifest,ensure_ascii=False))});")
  for i,artifact,e,target,typ,mime,encoding,share in [(1,'original',ea,'x/show/archive.gz','ORIGINAL_STORE_NAS','video/vnd.dlna.mpeg-tts','gzip','bob'),(2,'encoded:'+str(one),e1,'x/show/one.mp4','VIDEO_STORE_NAS','video/mp4',None,'alice')]:
   src=pathlib.Path(e['path']); contents=src.read_bytes()
   subprocess.run(['docker','exec','-i',nas,'sh','-c',f'mkdir -p /shares/{share}/x/show && chmod 777 /shares/{share}/x /shares/{share}/x/show && cat > /shares/{share}/{target}'],input=contents,check=True)
   sql(f"INSERT INTO created_file(id,splitted_file_id,file,size,mime,encoding,status) VALUES({i},1,{q(target)},{e['size']},{q(mime)},{q(encoding) if encoding else 'NULL'},'FILE_MOVED'); INSERT INTO upload_manifest(id,job_id,artifact_key,destination_key,nas_type,final_path,temporary_path,source_path,source_size,source_modified,source_sha256,checksum_required,state,created_file_id) VALUES({i},1,{q(sha(artifact.encode()))},{q(sha((typ+chr(0)+target).encode()))},{q(typ)},{q(target)},{q(target+'.owned-temp')},{q(e['path'])},{e['size']},{e['modified']},{q(e['sha256'])},1,'REGISTERED',{i});")
   src.unlink()
  def counts(): return sql('SELECT (SELECT COUNT(*) FROM processing_attempt),(SELECT COUNT(*) FROM processing_checkpoint),(SELECT COUNT(*) FROM upload_manifest),(SELECT COUNT(*) FROM created_file),processing_claim,callback_claim FROM processing_job WHERE id=1;')
  def snapshot():
   dump=docker('exec',db,'mariadb-dump','-uroot','-pissue75','--skip-comments','--compact','--no-create-info','--skip-extended-insert','--order-by-primary','test')
   local=sorted((str(p),sha(p.read_bytes()),p.stat().st_mtime_ns) for p in root.rglob('*') if p.is_file())
   remote=docker('exec',nas,'sh','-c','find /shares -type f -exec sha256sum {} +')
   return dump,local,sorted(remote.splitlines())
  def readonly(operation,args,code=0):
   before=snapshot()
   p=subprocess.run([java,'-jar',str(jar),operation,*args],env=env,capture_output=True,text=True,encoding='utf-8',timeout=60)
   assert p.returncode==code,(p.returncode,p.stdout,p.stderr)
   assert snapshot()==before,'inspection mutated DB/local/NAS'
   return json.loads(p.stdout)['inspection'] if p.stdout.strip() else None
  report=readonly('inspect',['--job-id','1','--json'])
  assert report['resumePossible'] and any(a['status']=='MISSING' for a in report['localArtifacts'])
  assert any(a['verification']=='SHA256_RECEIPT' for a in report['uploads'])
  assert report['callback']['outputs'][0]['file']['path']==str(one)
  sql(f"INSERT INTO upload_manifest(id,job_id,artifact_key,destination_key,nas_type,final_path,temporary_path,source_path,source_size,source_modified,source_sha256,checksum_required,state,created_file_id) SELECT 9,job_id,{q(sha(b'unknown-artifact'))},{q(sha(b'unknown-target'))},nas_type,'x/unknown','x/unknown-temp',source_path,source_size,source_modified,source_sha256,checksum_required,state,created_file_id FROM upload_manifest WHERE id=2")
  unknown=readonly('inspect',['--job-id','1','--json'])
  assert next(m for m in unknown['uploads'] if m['id']==9)['verification']=='UNCHECKED'
  sql('DELETE FROM upload_manifest WHERE id=9')
  readonly('recover',['--job-id','1','--dry-run','--json'])
  readonly('recover',['--job-id','1','--json'],2)
  # Explicit fixture transition using previously verified #63; #75 calls remain read-only.
  p=subprocess.run([java,'-jar',str(jar),'resume','--job-id','1','--json'],env=env,capture_output=True,text=True,timeout=60)
  assert p.returncode==0,(p.stdout,p.stderr)
  report=readonly('inspect',['--recording',str(raw),'--json'])
  assert report['jobState']=='COMPLETED' and report['resumePossible']
  readonly('recover',['--job-id','1','--dry-run','--json'])
  readonly('inspect',['--job-id','999','--json'],3)
  sql("UPDATE created_file SET size=size+1 WHERE id=2")
  report=readonly('inspect',['--job-id','1','--json'],4)
  assert any(a['status']=='SIZE_MISMATCH' for a in report['nasArtifacts'])
  sql("UPDATE created_file SET size=size-1 WHERE id=2")
  sql(f"INSERT INTO processing_job(id,identity_key,input_path,input_size,modified_millis,state) VALUES(2,{q(sha(b'unknown'))},{q(raw)},1,1,'SUBMISSION_UNKNOWN')")
  report=readonly('inspect',['--recording',str(raw),'--json'],4)
  assert report['status']=='AMBIGUOUS'
  readonly('recover',['--job-id','2','--dry-run','--json'],4)
  sql("UPDATE processing_job SET processing_claim='secret-owner' WHERE id=2")
  report=readonly('inspect',['--job-id','2','--json'],4)
  assert 'secret-owner' not in json.dumps(report)
  sql("INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) VALUES(2,'/missing-legacy.m2ts',0,11,'2026-01-01','1','legacy','channel',60,'SPLITTED'); INSERT INTO program(id,name,executed_file_id,status) VALUES(2,'legacy',2,'ERROR'); INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(2,2,'/missing-legacy-split',11,60,'COMPRESS_SAVED'); INSERT INTO created_file(id,splitted_file_id,file,size,mime,status) VALUES(4,2,'x/show/legacy.mp4',11,'video/mp4','FILE_MOVED');")
  legacy=readonly('inspect',['--recording','/missing-legacy.m2ts','--json'],4)
  assert not legacy['resumePossible'] and legacy['records'] and legacy['nasArtifacts']
  sql('DELETE FROM program WHERE id=2')
  orphan=readonly('inspect',['--recording','/missing-legacy.m2ts','--json'],4)
  assert orphan['status']=='INCONSISTENT' and orphan['nasArtifacts'][0]['status']=='UNCHECKED'
  denied=root/'denied'; denied.mkdir(); hidden=denied/'raw.m2ts'; hidden.write_bytes(b'data'); denied.chmod(0)
  try:
   sql(f"INSERT INTO processing_job(id,identity_key,input_path,input_size,modified_millis,state) VALUES(3,{q(sha(b'denied'))},{q(hidden)},4,1,'SUBMISSION_UNKNOWN')")
   report=readonly('inspect',['--job-id','3','--json'],4)
   assert report['localArtifacts'][0]['status']=='INACCESSIBLE'
  finally: denied.chmod(0o700)
  print('PASS actual JAR/MariaDB/Samba: partial callback missing local receipts, completed deleted original lookup, ambiguous path, unknown/active owner, size mismatch, UTF8 strictJSON, mandatory dry-run, DB dump/local hashes/NAS hashes unchanged')
finally:
 subprocess.run(['docker','stop',nas,db],capture_output=True)
