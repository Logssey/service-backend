"""Run inside lab-runner. Token never appears in output; no request data is configurable."""
import json, os, sys, urllib.request, urllib.error
BASE='http://reused-api-fixture:8080'
mode=sys.argv[1] if len(sys.argv)>1 else 'normal'
paths={'normal':'/api/v1/categories','crypto':'/internal/attack-lab/crypto/probe',
       'runtime':'/internal/attack-lab/runtime/java-spawns-shell',
       'unauthorized':'/internal/attack-lab/runtime/java-spawns-shell',
       'reject-body':'/internal/attack-lab/runtime/java-spawns-shell'}
if mode not in paths: raise SystemExit('Unknown scenario')
headers={'Content-Type':'text/plain'}
if mode!='unauthorized':headers['X-Attack-Lab-Token']=os.environ['ATTACK_LAB_TOKEN']
data=None if mode=='normal' else b'id' if mode=='reject-body' else b''
request=urllib.request.Request(BASE+paths[mode],data=data,headers=headers)
try:
    with urllib.request.urlopen(request,timeout=15) as response:
        body=json.load(response)
        print(json.dumps({'mode':mode,'status':response.status,'result':body if mode!='normal' else 'normal API succeeded'}))
except urllib.error.HTTPError as error:
    print(json.dumps({'mode':mode,'status':error.code}))
    if (mode,error.code) not in [('unauthorized',403),('reject-body',400)]:raise SystemExit(1)
