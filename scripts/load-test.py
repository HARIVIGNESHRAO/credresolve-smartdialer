#!/usr/bin/env python3
"""Bounded HTTP load probe. Creates one isolated synthetic campaign; never uses real telecom."""
import concurrent.futures, json, os, time, urllib.request
from pathlib import Path
base=os.getenv("API_URL","http://127.0.0.1:8081/api")
def request(path,body=None,method=None):
    req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers={"Content-Type":"application/json"},method=method)
    with urllib.request.urlopen(req,timeout=30) as response:return json.load(response)
c=request('/campaigns',dict(name='Load probe',mode='PROGRESSIVE',provider='A',maxActive=50,riskLimit=0))['id']
for i in range(50):
    agent=request('/agents',dict(name=f'Load agent {i}',campaignId=c))
    request(f"/agents/{agent['id']}/status",dict(status='AVAILABLE'),'PATCH')
request(f'/campaigns/{c}/start',{})
def tick(_):
    start=time.perf_counter();r=request('/dialer/progressive/tick',dict(campaignId=c));return(time.perf_counter()-start)*1000,r['approvedCalls']
start=time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool: outcomes=list(pool.map(tick,range(100)))
elapsed=time.perf_counter()-start
request(f'/campaigns/{c}/stop',{})
latencies=sorted(x[0] for x in outcomes)
result=dict(requests=100,workers=10,elapsedSeconds=elapsed,requestsPerSecond=100/elapsed,p50Ms=latencies[49],p95Ms=latencies[94],approvedByProbe=sum(x[1] for x in outcomes),campaignId=c,note='Local HTTP + PostgreSQL admission benchmark; scheduler may also approve calls. Not a provider throughput or production scaling benchmark.')
assert result['approvedByProbe']<=50
print(json.dumps(result,indent=2));(Path(__file__).resolve().parents[1]/'docs'/'load-results.json').write_text(json.dumps(result,indent=2)+'\n')
