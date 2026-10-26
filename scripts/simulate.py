#!/usr/bin/env python3
"""Run measured, reproducible experiments against the real backend."""
import json, os, urllib.request
from pathlib import Path
base=os.getenv("API_URL","http://127.0.0.1:8081/api")
results=[]
for scenario in "ABCD":
    config=dict(scenario=scenario,agents=50,durationSeconds=900,providerLatencyMs=500,failureRate=0.03,dropouts=20 if scenario=="D" else 0,seed=42)
    request=urllib.request.Request(base+"/simulations/run",data=json.dumps(config).encode(),headers={"Content-Type":"application/json"})
    with urllib.request.urlopen(request) as response: result=json.load(response)
    results.append(result)
    for mode in ("progressive","predictive"):
        m=result[mode]
        print(f"{scenario} {mode:11} initiated={m['callsInitiated']:4} connected={m['callsConnected']:4} utilization={m['agentUtilization']:.2%} abandoned={m['abandonedConnectedCalls']} fallback_ticks={m['fallbackEvents']}")
output=Path(__file__).resolve().parents[1]/"docs"/"simulation-results.json"
output.write_text(json.dumps(results,indent=2)+"\n")
print(f"Saved {output}")
