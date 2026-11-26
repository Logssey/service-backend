"""Render only isolated demo resources. Never reads production secrets or creates a public route."""
import json, sys
NS = 'reused-attack-lab'
image = sys.argv[1]
if '/logssey/attack-lab/reused-api@sha256:' not in image:
    raise SystemExit('A digest from the dedicated attack-lab image repository is required')
items = []
def add(kind, name, **extra):
    version = 'apps/v1' if kind == 'Deployment' else 'networking.k8s.io/v1' if kind == 'NetworkPolicy' else 'v1'
    obj = dict(apiVersion=version, kind=kind, metadata={'name':name, 'namespace':NS,
               'labels':{'logssey.test-fixture':'true'}}, **extra)
    items.append(obj)
    return obj
items.append({'apiVersion':'v1','kind':'Namespace','metadata':{'name':NS,'labels':{
    'logssey.io/environment':'attack-lab','logssey.test-fixture':'true',
    'pod-security.kubernetes.io/enforce':'restricted'}}})
def env(name, value): return {'name':name, 'value':value}
def secret(name, key): return {'name':name,'valueFrom':{'secretKeyRef':{'name':'lab-credentials','key':key}}}
def workload(name, img, port, variables, user=1000, memory='256Mi', volume=None, command=None):
    container = {'name':name, 'image':img,'imagePullPolicy':'IfNotPresent','env':variables,
        'securityContext':{'allowPrivilegeEscalation':False,'capabilities':{'drop':['ALL']},'readOnlyRootFilesystem':name!='db'},
        'resources':{'requests':{'cpu':'50m','memory':'64Mi'},'limits':{'cpu':'1000m','memory':memory}}}
    pod = {'automountServiceAccountToken':False,
        'dnsPolicy':'None','dnsConfig':{'nameservers':['10.96.0.3'],
            'searches':[NS+'.svc.cluster.local','svc.cluster.local','cluster.local'],
            'options':[{'name':'ndots','value':'5'}]},
        'securityContext':{'runAsNonRoot':True,'runAsUser':user,
        'runAsGroup':user,'fsGroup':user,'seccompProfile':{'type':'RuntimeDefault'}},'containers':[container],
        'volumes':[{'name':'tmp','emptyDir':{}}]}
    container['volumeMounts']=[{'name':'tmp','mountPath':'/tmp'}]
    if volume:
        pod['volumes'].append({'name':'data','emptyDir':{}})
        container['volumeMounts'].append({'name':'data','mountPath':volume})
    if command: container['command']=command
    if port:
        container['ports']=[{'containerPort':port}]
        container['readinessProbe']={'tcpSocket':{'port':port},'initialDelaySeconds':5,'periodSeconds':5}
        add('Service',name,spec={'type':'ClusterIP','selector':{'app':name},'ports':[{'port':port,'targetPort':port}]})
    add('Deployment',name,spec={'replicas':1,'selector':{'matchLabels':{'app':name}},
        'template':{'metadata':{'labels':{'app':name,'scenario-id':'REUSED-DEMO-01','logssey.test-fixture':'true'}},'spec':pod}})
workload('db','postgres:18.6-alpine',5432,[env('POSTGRES_DB','reused'),env('POSTGRES_USER','lab'),
    secret('POSTGRES_PASSWORD','db-password'),env('PGDATA','/var/lib/postgresql/data/pgdata')],70,'256Mi','/var/lib/postgresql/data')
workload('redis','redis:7.4-alpine',6379,[],999,'128Mi',command=['redis-server','--save','','--appendonly','no'])
workload('reused-api-fixture',image,8080,[env('SPRING_PROFILES_ACTIVE','attack-lab'),env('ATTACK_LAB_ENABLED','true'),
    env('POD_NAMESPACE',NS),secret('ATTACK_LAB_TOKEN','token'),secret('JWT_SECRET','jwt'),
    env('SPRING_DATASOURCE_URL','jdbc:postgresql://db:5432/reused'),env('SPRING_DATASOURCE_USERNAME','lab'),
    secret('SPRING_DATASOURCE_PASSWORD','db-password'),env('SPRING_DATA_REDIS_HOST','redis'),
    env('AWS_EC2_METADATA_DISABLED','true')],memory='768Mi')
workload('lab-runner','python:3.13-alpine',None,[secret('ATTACK_LAB_TOKEN','token')],1000,'128Mi',
    command=['python','-c','import time; time.sleep(86400)'])
add('NetworkPolicy','default-deny',spec={'podSelector':{},'policyTypes':['Ingress','Egress']})
add('NetworkPolicy','dns-only',spec={'podSelector':{},'policyTypes':['Egress'],'egress':[{'to':[{
    'namespaceSelector':{'matchLabels':{'kubernetes.io/metadata.name':'kube-system'}},
    'podSelector':{'matchLabels':{'k8s-app':'kube-dns'}}}],
    'ports':[{'protocol':'UDP','port':53},{'protocol':'TCP','port':53}]}]})
for source, target, port in [('lab-runner','reused-api-fixture',8080),('reused-api-fixture','db',5432),('reused-api-fixture','redis',6379)]:
    add('NetworkPolicy',f'{source}-to-{target}',spec={'podSelector':{'matchLabels':{'app':source}},
        'policyTypes':['Egress'],'egress':[{'to':[{'podSelector':{'matchLabels':{'app':target}}}],
        'ports':[{'protocol':'TCP','port':port}]}]})
    add('NetworkPolicy',f'{target}-from-{source}',spec={'podSelector':{'matchLabels':{'app':target}},
        'policyTypes':['Ingress'],'ingress':[{'from':[{'podSelector':{'matchLabels':{'app':source}}}],
        'ports':[{'protocol':'TCP','port':port}]}]})
json.dump({'apiVersion':'v1','kind':'List','items':items},sys.stdout,indent=2)
