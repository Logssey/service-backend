import datetime,hashlib,json,os,pathlib,secrets,subprocess,tempfile,time,urllib.request,importlib.util,base64,argparse
HERE=pathlib.Path(__file__).resolve().parent
ap=argparse.ArgumentParser(description="Record actual coverage in disposable internal-network Docker containers")
ap.add_argument('--dashboard-backend',required=True,type=pathlib.Path)
ap.add_argument('--evidence-dir',required=True,type=pathlib.Path,help='Contains sbom.cdx.json and reach-result.json')
ap.add_argument('--image-info',required=True,type=pathlib.Path)
ap.add_argument('--local-image',default='logssey-attack-lab:demo')
a=ap.parse_args()
OUT=a.evidence_dir.resolve()/'l3';OUT.mkdir(parents=True,exist_ok=True)
TOOLS=a.evidence_dir.resolve()/'jacoco-tools';TOOLS.mkdir(exist_ok=True)
BASE=a.dashboard_backend.resolve()
JAR=HERE.parents[1]/'build/libs/service-backend-0.0.1-SNAPSHOT.jar'
SCOPE='com.reused.*:org.bouncycastle.*'
for name,artifact,suffix,checksum in [('jacocoagent.jar','agent','runtime','47e700ccb0fdb9e27c5241353f8161938f4e53c3561dd35e063c5fe88dc3349b'),('jacococli.jar','cli','nodeps','8f748683833d4dc4d72cea5d6b43f49344687b831e0582c97bcb9b984e3de0a3')]:
    dest=TOOLS/name
    if not dest.exists():
        with urllib.request.urlopen(f'https://repo.maven.apache.org/maven2/org/jacoco/org.jacoco.{artifact}/0.8.13/org.jacoco.{artifact}-0.8.13-{suffix}.jar') as r: dest.write_bytes(r.read())
    assert hashlib.sha256(dest.read_bytes()).hexdigest()==checksum
def run(*args):
    return subprocess.run([str(x) for x in args],check=True,capture_output=True,text=True,encoding='utf-8',errors='replace').stdout.strip()
name='logssey-demo-l3-'+secrets.token_hex(4)
containers=[]
image=json.loads(a.image_info.read_text(encoding='utf-8'))
inspect=json.loads(run('docker','image','inspect',a.local_image))[0]
assert inspect['Config']['Labels']['org.opencontainers.image.revision']==image['sourceCommit'], 'Image revision does not match evidence'
assert '/logssey/attack-lab/reused-api@sha256:' in image['image']
started=datetime.datetime.now(datetime.timezone.utc).isoformat()
run('docker','network','create','--internal','--label','logssey.test-fixture=true',name)
try:
 with tempfile.TemporaryDirectory() as temp:
    temp=pathlib.Path(temp); token=secrets.token_urlsafe(48); password=secrets.token_urlsafe(32)
    services=[('db','postgres:18.6-alpine',{'POSTGRES_DB':'reused','POSTGRES_USER':'lab','POSTGRES_PASSWORD':password}),('redis','redis:7.4-alpine',{})]
    for alias,ref,env in services:
        envfile=temp/(alias+'.env');envfile.write_text('\n'.join(k+'='+v for k,v in env.items()))
        run('docker','pull',ref)
        cid=run('docker','run','-d','--name',name+'-'+alias,'--label','logssey.test-fixture=true','--network',name,'--network-alias',alias,'--env-file',envfile,ref)
        containers.append(cid)
    env={'SPRING_PROFILES_ACTIVE':'attack-lab','ATTACK_LAB_ENABLED':'true','POD_NAMESPACE':'reused-attack-lab','ATTACK_LAB_TOKEN':token,'JWT_SECRET':secrets.token_urlsafe(64),'SPRING_DATASOURCE_URL':'jdbc:postgresql://db:5432/reused','SPRING_DATASOURCE_USERNAME':'lab','SPRING_DATASOURCE_PASSWORD':password,'SPRING_DATA_REDIS_HOST':'redis','AWS_EC2_METADATA_DISABLED':'true',
         'JAVA_TOOL_OPTIONS':'-javaagent:/jacoco/jacocoagent.jar=output=tcpserver,address=*,port=36300,includes='+SCOPE}
    envfile=temp/'app.env';envfile.write_text('\n'.join(k+'='+v for k,v in env.items()))
    cid=run('docker','run','-d','--name',name+'-app','--label','logssey.test-fixture=true','--network',name,'--network-alias','app','--env-file',envfile,'-v',str(TOOLS)+':/jacoco:ro','--memory','768m',a.local_image)
    containers.append(cid)
    run('docker','pull','python:3.13-alpine')
    runner=run('docker','run','-d','--network',name,'--label','logssey.test-fixture=true','python:3.13-alpine','python','-c','import time; time.sleep(900)')
    containers.append(runner)
    def sender(base_url,method,path,headers,body):
        code="import sys,json,urllib.request,urllib.error,base64; x=json.load(sys.stdin); req=urllib.request.Request('http://app:8080'+x['path'],method=x['method'],headers=x['headers'],data=base64.b64decode(x['body']) if x['body'] is not None else None)\ntry:\n r=urllib.request.urlopen(req,timeout=15)\nexcept urllib.error.HTTPError as e:\n r=e\nprint(json.dumps({'status':r.code,'body':base64.b64encode(r.read()).decode()}))"
        req=json.dumps({'path':path,'method':method,'headers':headers,'body':base64.b64encode(body).decode() if body is not None else None})
        p=subprocess.run(['docker','exec','-i',runner,'python','-c',code],input=req,capture_output=True,text=True,check=True)
        result=json.loads(p.stdout);return result['status'],base64.b64decode(result['body'])
    for attempt in range(90):
        try:
            status,_=sender('', 'GET','/api/v1/categories',{},None)
            if status==200:break
        except Exception:time.sleep(2)
    else:raise RuntimeError('L3 app did not become ready; logs intentionally not printed')
    print('L3 app ready; running existing scenario recorder',flush=True)
    sc={'schema':'reach-l3-scenario/v1','version':'reused-attack-lab-20261126','app':{'port':8080,'env':{k:v for k,v in env.items() if k!='JAVA_TOOL_OPTIONS'},'ready':{'path':'/api/v1/categories','status':200}},'steps':[
      {'name':'normal categories','request':'GET /api/v1/categories'},
      {'name':'benign crypto probe','request':'POST /internal/attack-lab/crypto/probe','headers':{'X-Attack-Lab-Token':token}}]}
    scenario=temp/'scenario.json';scenario.write_text(json.dumps(sc),encoding='utf-8')
    script=BASE/'reach-analyzer/verdict/l3_runner.py'
    spec=importlib.util.spec_from_file_location('demo_l3_runner',script);mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
    (OUT/'segments').mkdir(exist_ok=True)
    def cut(segment):
        remote='/tmp/'+segment+'.exec'
        run('docker','exec','-e','JAVA_TOOL_OPTIONS=',cid,'java','-Xmx64m','-jar','/jacoco/jacococli.jar','dump','--address','127.0.0.1','--port','36300','--destfile',remote,'--reset')
        run('docker','cp',cid+':'+remote,OUT/'segments'/(segment+'.exec'))
        mod.check_segment(OUT/'segments'/(segment+'.exec'))
    problems=mod.validate(sc)
    if problems:raise RuntimeError(str(problems))
    steps=mod.run(sc,'http://app:8080',sender=sender,cut=cut)
    (OUT/'steps.json').write_text(json.dumps(steps),encoding='utf-8')
    assert steps['requests']['failed']==0
    record={'observationMethod':'COVERAGE','observedAt':started,'scenario':steps['scenario'],'build':{'imageDigest':image['digest']},'configKeys':steps['configKeys'],'invocations':[{'segment':s['segment'],'name':s['name'],'request':s['method']+' '+s['path'],'count':s['count'],'ok':s['ok'],'failed':s['failed'],'skipped':s['skipped']} for s in steps['steps']],'requests':steps['requests'],'scope':SCOPE,'javaRelease':21,'jacoco':'0.8.13'}
    (OUT/'run.json').write_text(json.dumps(record),encoding='utf-8')
finally:
 for cid in reversed(containers):run('docker','rm','-f',cid)
 run('docker','network','rm',name)
print(run('python',BASE/'reach-analyzer/verdict/l3_runner.py','classes','--jar',JAR,'--out',OUT/'classes','--spec',BASE/'src/main/resources/reach/vuln-functions.json','--reach',OUT.parent/'reach-result.json'),flush=True)
(OUT/'xml').mkdir(exist_ok=True)
for segment in (OUT/'segments').glob('*.exec'):
    result=run('java','-jar',TOOLS/'jacococli.jar','report',segment,'--classfiles',OUT/'classes','--xml',OUT/'xml'/(segment.stem+'.xml'))
    if 'does not match' in result:raise RuntimeError('Coverage class mismatch')
print(run('python',BASE/'reach-analyzer/verdict/l3.py','--spec',BASE/'src/main/resources/reach/vuln-functions.json','--sbom',OUT.parent/'sbom.cdx.json','--reach',OUT.parent/'reach-result.json','--segments',OUT/'xml','--scope',SCOPE,'--out',OUT/'l3-result.json'),flush=True)
print(run('python',BASE/'reach-analyzer/verdict/l3_runner.py','pack','--segments',OUT/'segments','--run',OUT/'run.json','--out',OUT/'record.tar.xz'),flush=True)
