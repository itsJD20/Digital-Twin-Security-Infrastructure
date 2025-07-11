#!/usr/bin/env python3
"""
Attack simulation server that mocks Ditto API with invalid signatures.
This FastAPI server runs on port 8080 and provides fake data with wrong signatures
to test the signature verification functionality of the exporter and verifier.

Only implements the routes actually used by the exporter:
- /api/2/things
- /api/2/things/{thing_id}/features
"""

import json
import random
import base64
import secrets
import subprocess
import signal
import os
import time
from datetime import datetime, timezone
from typing import Dict, List, Any
from fastapi import FastAPI, HTTPException
import uvicorn

app = FastAPI(title="Ditto Attack Simulator", version="2.0.0")

# Configuration matching exporter_config.json
MOCK_THINGS = [
    {
        "thing_id": "openplc:103b3864-923d-49d0-8f1c-8c6810a025c3",
        "features": {
            "coughsyrupvalve": ["open"]
        }
    },
    {
        "thing_id": "hmi:bbf883ed-b192-4fb2-b432-41664e91d059", 
        "features": {
            "coughsyrupvalve": ["input_level", "input_blocked", "output_level", "output_blocked", "sender"]
        }
    }
]

def generate_random_signature() -> str:
    """Generate a random base64 string that looks like a signature but is invalid."""
    random_bytes = secrets.token_bytes(256)  # RSA 2048-bit signature is 256 bytes
    return base64.b64encode(random_bytes).decode('utf-8')

def kill_process_on_port(port: int) -> None:
    """Kill any process running on the specified port."""
    try:
        # Find process using lsof (works on macOS and Linux)
        result = subprocess.run(
            ['lsof', '-ti', f':{port}'],
            capture_output=True,
            text=True,
            timeout=10
        )
        
        if result.returncode == 0 and result.stdout.strip():
            pids = result.stdout.strip().split('\n')
            for pid in pids:
                if pid:
                    try:
                        pid_int = int(pid)
                        print(f"🔪 Killing process {pid_int} on port {port}")
                        os.kill(pid_int, signal.SIGTERM)
                        time.sleep(1)  # Give process time to terminate gracefully
                        
                        # Check if process still exists, force kill if needed
                        try:
                            os.kill(pid_int, 0)  # Check if process exists
                            print(f"🔪 Force killing process {pid_int}")
                            os.kill(pid_int, signal.SIGKILL)
                        except OSError:
                            pass  # Process already terminated
                            
                    except (ValueError, OSError) as e:
                        print(f"⚠️  Error killing process {pid}: {e}")
        else:
            print(f"✅ No process found running on port {port}")
            
    except subprocess.TimeoutExpired:
        print(f"⚠️  Timeout checking for processes on port {port}")
    except FileNotFoundError:
        # lsof not available, try alternative approach using netstat
        try:
            result = subprocess.run(
                ['netstat', '-an', '-p', 'tcp'],
                capture_output=True,
                text=True,
                timeout=10
            )
            
            if result.returncode == 0:
                lines = result.stdout.split('\n')
                for line in lines:
                    if f'.{port} ' in line and 'LISTEN' in line:
                        print(f"⚠️  Process detected on port {port} but cannot kill (lsof not available)")
                        break
                        
        except (subprocess.TimeoutExpired, FileNotFoundError):
            print(f"⚠️  Cannot check for processes on port {port}")
    except Exception as e:
        print(f"⚠️  Error checking port {port}: {e}")

def get_random_property_value(property_name: str) -> Any:
    """Generate random values for different property types."""
    if property_name == "open":
        return random.choice([True, False])
    elif "level" in property_name:
        return round(random.uniform(0.0, 100.0), 2)
    elif "blocked" in property_name:
        return random.choice([True, False])
    else:
        return random.choice([
            random.randint(1, 1000),
            round(random.uniform(0, 100), 2),
            random.choice([True, False])
        ])

@app.get("/api/2/things")
async def get_things():
    """Mock Ditto things endpoint - used by exporter get_ditto_things()."""
    things = []
    for thing_config in MOCK_THINGS:
        things.append({
            "thingId": thing_config["thing_id"],
            "policyId": f"policy:{thing_config['thing_id']}",
            "_created": datetime.now(timezone.utc).isoformat(),
            "_modified": datetime.now(timezone.utc).isoformat(),
            "_revision": random.randint(1, 100)
        })
    
    print(f"🎯 Attack API: Serving {len(things)} things with fake signatures")
    return things

@app.get("/api/2/things/{thing_id}/features")
async def get_features(thing_id: str):
    """Mock Ditto features endpoint - used by exporter get_ditto_features()."""
    # Find the thing configuration
    thing_config = None
    for config in MOCK_THINGS:
        if config["thing_id"] == thing_id:
            thing_config = config
            break
    
    if not thing_config:
        raise HTTPException(status_code=404, detail=f"Thing {thing_id} not found")
    
    features = {}
    for feature_id, property_names in thing_config["features"].items():
        properties = {}
        
        # Generate random values for all properties
        for prop_name in property_names:
            properties[prop_name] = get_random_property_value(prop_name)
        
        # Add INVALID signature to test signature verification
        properties["signature"] = generate_random_signature()
        
        features[feature_id] = {
            "properties": properties
        }
    
    print(f"🚨 Attack API: Serving features for {thing_id} with INVALID signatures")
    
    return features

def main():
    """Run the attack server."""
    print("🚨 Starting Ditto Attack Simulator")
    print("=" * 40)
    
    # Kill any existing process on port 8080
    kill_process_on_port(8080)
    
    print(f"📡 Server: http://127.0.0.1:7070")
    print(f"🎯 Purpose: Provide invalid signatures to test verification")
    print(f"🔧 Mocking {len(MOCK_THINGS)} things:")
    
    for thing in MOCK_THINGS:
        print(f"   - {thing['thing_id']}")
        for feature_id, properties in thing["features"].items():
            print(f"     └─ {feature_id}: {properties}")
    
    print("\n🛡️  To test:")
    print('1. Set "verify_signatures": true in exporter_config.json')
    print("2. Run exporter.py - should reject features with invalid signatures")
    print("3. Run verifier.py - should alert on signature failures")
    print("=" * 40)
    
    # Run the server
    uvicorn.run(app, host="127.0.0.1", port=7070, log_level="warning")

if __name__ == "__main__":
    main()
