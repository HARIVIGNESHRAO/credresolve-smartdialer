#!/usr/bin/env python3
import json, os, urllib.request
base=os.getenv('API_URL','http://127.0.0.1:8081/api')
for endpoint in ['/campaigns','/agents','/calls','/providers/health','/dashboard/metrics','/safety/decisions']:
    with urllib.request.urlopen(base+endpoint) as r:data=json.load(r);assert r.status==200
    print('PASS GET '+endpoint)
frontend=os.getenv('FRONTEND_URL','http://127.0.0.1:3000')
with urllib.request.urlopen(frontend) as r:assert b'SmartDialer' in r.read()
with urllib.request.urlopen(frontend+'/api/dashboard/metrics') as r:assert 'agents' in json.load(r)
print('PASS frontend HTML and backend proxy')
