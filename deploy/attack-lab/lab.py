"""Operate only the isolated lab through an explicitly named SSM control-plane host."""
import argparse,base64,json,pathlib,subprocess,tempfile,time
HERE=pathlib.Path(__file__).resolve().parent
NS='reused-attack-lab'
ap=argparse.ArgumentParser(description=__doc__)
ap.add_argument('action',choices=['status','collect','normal','unauthorized','reject-body','crypto','runtime','restart','cleanup'])
ap.add_argument('--instance',required=True)
ap.add_argument('--profile',default='new')
ap.add_argument('--region',default='ap-northeast-1')
a=ap.parse_args()
prefix='set -eu\nexport KUBECONFIG=/etc/kubernetes/admin.conf\n'
guard='[ "$(kubectl get namespace '+NS+' -o jsonpath=\'{.metadata.labels.logssey\\.io/environment}\')" = "attack-lab" ]\n'
if a.action=='status':
    script=prefix+guard+f'kubectl -n {NS} get pods,services,networkpolicies\n'
elif a.action=='collect':
    script=prefix+guard+'''job="reach-l1-demo-manual-$(date +%s)"
kubectl -n reach create job --from=cronjob/reach-l1-demo-collector "$job"
kubectl -n reach wait --for=condition=Complete "job/$job" --timeout=180s
kubectl -n reach logs "job/$job"
'''
elif a.action=='cleanup':
    script=prefix+"bash <<'LAB_CLEANUP'\n"+(HERE/'cleanup.sh').read_text(encoding='utf-8')+"\nLAB_CLEANUP\n"
elif a.action=='restart':
    script=prefix+guard+f'kubectl -n {NS} rollout restart deployment/reused-api-fixture\nkubectl -n {NS} rollout status deployment/reused-api-fixture --timeout=180s\n'
else:
    script=prefix+guard+f"kubectl -n {NS} exec -i deploy/lab-runner -- python - {a.action} <<'PY'\n"+(HERE/'probe.py').read_text(encoding='utf-8')+'\nPY\n'
def aws(*args):
    result=subprocess.run(['aws','--profile',a.profile,'--region',a.region,'ssm',*args],capture_output=True,check=True)
    try: raw=result.stdout.decode('utf-8')
    except UnicodeDecodeError: raw=result.stdout.decode('cp949')
    return json.loads(raw)
with tempfile.TemporaryDirectory() as temp:
    path=pathlib.Path(temp)/'params.json'
    path.write_text(json.dumps({'commands':[script],'executionTimeout':['300']}),encoding='utf-8')
    command=aws('send-command','--instance-ids',a.instance,'--document-name','AWS-RunShellScript','--parameters','file://'+str(path))['Command']['CommandId']
print('SSM command:',command,flush=True)
for _ in range(100):
    time.sleep(3)
    result=aws('get-command-invocation','--instance-id',a.instance,'--command-id',command)
    if result['Status'] in ['Pending','InProgress','Delayed']: continue
    print(result.get('StandardOutputContent',''))
    if result['Status']!='Success':
        print(result.get('StandardErrorContent',''));raise SystemExit(1)
    break
else:raise SystemExit('SSM still running; check command ID before retrying')
