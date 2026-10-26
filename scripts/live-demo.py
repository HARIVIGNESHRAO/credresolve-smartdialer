#!/usr/bin/env python3
"""Observe real scheduler/provider lifecycle with synthetic agents and bounded runtime."""
import json, os, time, urllib.request
from collections import Counter
from pathlib import Path
base=os.getenv('API_URL','http://127.0.0.1:8081/api')
def request(path,body=None,method=None):
    r=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'},method=method)
    with urllib.request.urlopen(r,timeout=20) as response:return json.load(response)
campaign=request('/campaigns',dict(name='Lifecycle smoke demo',mode='PROGRESSIVE',provider='A',maxActive=5,riskLimit=0))['id']
agents=[]
for i in range(5):
    a=request('/agents',dict(name=f'Lifecycle demo {i}',campaignId=campaign))['id'];agents.append(a)
    request(f'/agents/{a}/status',dict(status='AVAILABLE'),'PATCH')
request(f'/campaigns/{campaign}/start',{})
observed=set();samples=[];started=time.monotonic()
try:
    for second in range(28):
        for a in agents:request(f'/agents/{a}/heartbeat',{})
        calls=[c for c in request('/calls') if c['campaign_id']==campaign]
        counts=dict(Counter(c['state'] for c in calls));observed.update(counts)
        if second%3==0:print(f't={second:2}s {counts}');samples.append(dict(second=second,states=counts))
        if 'COMPLETED' in observed and 'CONNECTED' in observed:break
        time.sleep(1)
finally:request(f'/campaigns/{campaign}/stop',{})
assert 'CONNECTED' in observed and 'COMPLETED' in observed, f'Expected connection and completion, observed {observed}'
result=dict(campaignId=campaign,elapsedSeconds=time.monotonic()-started,observedStates=sorted(observed),samples=samples)
(Path(__file__).resolve().parents[1]/'docs'/'live-demo-results.json').write_text(json.dumps(result,indent=2)+'\n')
print('PASS real scheduler → durable dispatch → provider → callback → terminal release')
