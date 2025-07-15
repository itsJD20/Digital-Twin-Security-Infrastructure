from datetime import datetime
import time
import requests
import json
import base64
import os
from typing import Dict, List, Any, Optional
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

# Configuration
POLL_INTERVAL = 5  # seconds
CONFIG_FILE = "exporter_config.json"

# Helper functions
def load_config() -> Dict[str, Any]:
    """Load configuration from config file."""
    try:
        if os.path.exists(CONFIG_FILE):
            with open(CONFIG_FILE, 'r') as f:
                config = json.load(f)
                print(f"Loaded configuration from {CONFIG_FILE}")
                return config
        else:
            print(f"Config file {CONFIG_FILE} not found, exporting nothing")
            return {"data_to_export": {"sources": []}}
    except Exception as e:
        print(f"Error loading config: {e}, exporting nothing")
        return {"data_to_export": {"sources": []}}


def get_export_config(source_url: str, thing_id: str, feature_id: str, config: Dict[str, Any]) -> Optional[List[str]]:
    """Get properties to export for a source/thing/feature combination. Returns None if not configured."""
    sources_config = config.get("data_to_export", {}).get("sources", [])
    
    for source_config in sources_config:
        config_source_url = source_config.get("source_url", "")
        
        # Check if this source matches
        if config_source_url == source_url:
            things_config = source_config.get("things", [])
            
            for thing_config in things_config:
                config_thing_id = thing_config.get("thing_id", "")
                
                # Check if this thing matches (exact match or wildcard)
                if config_thing_id == thing_id or config_thing_id == "*":
                    features = thing_config.get("features", [])
                    downtime_start = datetime.fromisoformat(thing_config.get("downtime", {}).get("start"))
                    downtime_end = datetime.fromisoformat(thing_config.get("downtime", {}).get("end"))
                    time_now = datetime.now()
                    if downtime_start <= time_now <= downtime_end:
                        print(f"Skipping thing {thing_id} due to downtime from {downtime_start} to {downtime_end}")
                        return None
                    for feature_config in features:
                        config_feature_id = feature_config.get("feature_id", "")
                        
                        # Check if this feature matches (exact match or wildcard)
                        if config_feature_id == feature_id or config_feature_id == "*":
                            return feature_config.get("properties", [])
    
    return None


def encode_id(raw_id: str) -> str:
    """UTF-8 encode then URL-safe Base64 encode the ID."""
    utf8_bytes = raw_id.encode('utf-8')
    b64_bytes = base64.urlsafe_b64encode(utf8_bytes)
    return b64_bytes.decode('ascii')


def verify_feature_signature(feature_data: Dict[str, Any], public_key_path: str = "public_key.pem") -> bool:
    """Verify the signature of a feature's properties."""
    try:
        feature_data = feature_data["properties"]  # Ensure we only work with properties
        if "signature" not in feature_data:
            print("Warning: No signature found in feature data")
            return False
            
        signature_b64 = feature_data.get("signature")
        if not signature_b64:
            print("Warning: Empty signature in feature data")
            return False
            
        # Create payload without signature for verification
        payload = {k: v for k, v in feature_data.items() if k != "signature"}
        payload_bytes = json.dumps(payload, sort_keys=True).encode('utf-8')
        print(payload, "=" * 20)
        
        # Decode signature
        try:
            signature = base64.b64decode(signature_b64)
        except Exception as e:
            print(f"Warning: Invalid signature format: {e}")
            return False
        
        # Load public key
        if not os.path.exists(public_key_path):
            print(f"Warning: Public key file not found: {public_key_path}")
            return False
            
        with open(public_key_path, "rb") as key_file:
            public_key = serialization.load_pem_public_key(key_file.read())
            print(public_key,"=" * 20)
        
        # Verify signature
        public_key.verify(
            signature,
            payload_bytes,
            padding.PSS(
                mgf=padding.MGF1(hashes.SHA256()),
                salt_length=padding.PSS.MAX_LENGTH
            ),
            hashes.SHA256()
        )
        return True
        
    except Exception as e:
        print(f"Warning: Signature verification failed: {e}")
        print(e, "=" * 20)
        return False


def get_ditto_things(source_url: str, headers: Dict[str, str]):
    """Get things from a specific Ditto source."""
    endpoint = f"{source_url}/things"
    response = requests.get(endpoint, headers=headers)
    # print(f"GET {endpoint} -> {response.status_code}")
    # print(response.text)
    response.raise_for_status()
    return response.json()


def get_basyx_shell(thing_id: str, basyx_url: str):
    """Get shell from BaSyx."""
    encoded = encode_id(thing_id)
    url = f"{basyx_url}/shells/{encoded}"
    response = requests.get(url)
    # print(f"GET {url} -> {response.status_code}")
    # print(response.text)
    return response if response.status_code != 404 else None


def create_basyx_shell(thing_id: str, basyx_url: str):
    """Create shell in BaSyx."""
    payload = {
        "id": thing_id,
        "idShort": thing_id.split(":")[0].upper(),
        "assetInformation": {
            "assetKind": "INSTANCE",
            "globalAssetId": thing_id
        }
    }
    endpoint = f"{basyx_url}/shells"
    response = requests.post(endpoint, json=payload)
    # print(f"POST {endpoint} -> {response.status_code}")
    # print(response.text)
    response.raise_for_status()
    print(f"Created shell for thing: {thing_id}")


def get_basyx_submodel(submodel_id: str, basyx_url: str):
    """Get submodel from BaSyx."""
    encoded = encode_id(submodel_id)
    url = f"{basyx_url}/submodels/{encoded}"
    response = requests.get(url)
    return response if response.status_code != 404 else None


def delete_basyx_shell(thing_id: str, basyx_url: str):
    """Delete a shell from BaSyx."""
    try:
        encoded = encode_id(thing_id)
        url = f"{basyx_url}/shells/{encoded}"
        response = requests.delete(url)
        if response.status_code == 200:
            print(f"Deleted shell: {thing_id}")
        return response.status_code == 200
    except Exception as e:
        print(f"Error deleting shell {thing_id}: {e}")
        return False


def delete_basyx_submodel(submodel_id: str, basyx_url: str):
    """Delete a submodel from BaSyx."""
    try:
        encoded = encode_id(submodel_id)
        url = f"{basyx_url}/submodels/{encoded}"
        response = requests.delete(url)
        if response.status_code == 200:
            print(f"Deleted submodel: {submodel_id}")
        return response.status_code == 200
    except Exception as e:
        print(f"Error deleting submodel {submodel_id}: {e}")
        return False


def delete_basyx_element(submodel_id: str, element_id: str, basyx_url: str):
    """Delete a submodel element from BaSyx."""
    try:
        encoded_submodel = encode_id(submodel_id)
        url = f"{basyx_url}/submodels/{encoded_submodel}/submodel-elements/{element_id}"
        response = requests.delete(url)
        if response.status_code == 200:
            print(f"Deleted element {element_id} from submodel {submodel_id}")
        return response.status_code == 200
    except Exception as e:
        print(f"Error deleting element {element_id} from submodel {submodel_id}: {e}")
        return False


def get_basyx_submodel_elements(submodel_id: str, basyx_url: str):
    """Get all elements from a BaSyx submodel."""
    try:
        encoded_submodel = encode_id(submodel_id)
        url = f"{basyx_url}/submodels/{encoded_submodel}/submodel-elements"
        response = requests.get(url)
        if response.status_code == 200:
            return response.json().get("result", [])
        return []
    except Exception as e:
        print(f"Error getting elements from submodel {submodel_id}: {e}")
        return []


def cleanup_basyx_resources(config: Dict[str, Any]):
    """Remove BaSyx shells, submodels, and elements that correspond to filtered out Ditto items."""
    print("Starting BaSyx cleanup for filtered items...")
    
    basyx_config = config.get("basyx", {})
    basyx_url = basyx_config.get("url")
    if not basyx_url:
        print("Error: BaSyx URL not configured. Skipping cleanup.")
        return
        
    sources_config = config.get("data_to_export", {}).get("sources", [])
    
    try:
        items_to_remove = []
        elements_to_remove = []
        
        # Process each source
        for source_config in sources_config:
            source_url = source_config.get("source_url", "")
            auth_header = source_config.get("auth_header", "")
            headers = {"Authorization": auth_header} if auth_header else {}
            
            print(f"Processing source: {source_url}")
            
            # Get all Ditto things from this source
            things = get_ditto_things(source_url, headers)
            
            for thing in things:
                thing_id = thing.get("thingId")
                if not thing_id:
                    continue
                    
                # Check if this thing should be exported at all
                thing_has_exports = False
                features = get_ditto_features(thing_id, source_url, headers)
                
                for feature_id, feature_details in features.items():
                    properties_to_export = get_export_config(source_url, thing_id, feature_id, config)
                    if properties_to_export is not None:
                        thing_has_exports = True
                        break
                
                # If thing has no exports, mark its shell for removal
                if not thing_has_exports:
                    items_to_remove.append(('shell', thing_id))
                    print(f"Thing {thing_id} from {source_url} filtered out completely - will remove shell")
                    
                    # Also mark all its submodels for removal
                    for feature_id in features.keys():
                        submodel_id = f"{thing_id}:{feature_id}"
                        items_to_remove.append(('submodel', submodel_id))
                        print(f"Submodel {submodel_id} filtered out - will remove")
                else:
                    # Thing has some exports, check individual features
                    for feature_id, feature_details in features.items():
                        properties_to_export = get_export_config(source_url, thing_id, feature_id, config)
                        if properties_to_export is None:
                            # This feature is filtered out completely
                            submodel_id = f"{thing_id}:{feature_id}"
                            items_to_remove.append(('submodel', submodel_id))
                            print(f"Feature {feature_id} of thing {thing_id} filtered out - will remove submodel {submodel_id}")
                        else:
                            # Feature is exported, but check individual properties
                            submodel_id = f"{thing_id}:{feature_id}"
                            all_properties = feature_details.get("properties", {})
                            
                            # Determine which properties should be kept
                            if "*" not in properties_to_export:
                                # Only specific properties are configured
                                for prop_name in all_properties.keys():
                                    if prop_name not in properties_to_export:
                                        # This property is filtered out
                                        elements_to_remove.append((submodel_id, prop_name))
                                        print(f"Property {prop_name} of feature {feature_id} filtered out - will remove element")
        
        # Remove the identified items
        shells_deleted = 0
        submodels_deleted = 0
        elements_deleted = 0
        
        for item_type, item_id in items_to_remove:
            if item_type == 'shell':
                if delete_basyx_shell(item_id, basyx_url):
                    shells_deleted += 1
            elif item_type == 'submodel':
                if delete_basyx_submodel(item_id, basyx_url):
                    submodels_deleted += 1
        
        # Remove filtered properties
        for submodel_id, element_id in elements_to_remove:
            if delete_basyx_element(submodel_id, element_id, basyx_url):
                elements_deleted += 1
        
        print(f"Cleanup completed: {shells_deleted} shells, {submodels_deleted} submodels, and {elements_deleted} elements removed")
        
    except Exception as e:
        print(f"Error during cleanup: {e}")
        print("Continuing with export...")


def create_basyx_submodel(thing_id: str, submodel_id: str, basyx_url: str):
    """Create submodel in BaSyx."""
    submodel_name = submodel_id.split(":")[-1] if ":" in submodel_id else submodel_id

    # Create submodel
    payload = {
        "id": submodel_id,
        "idShort": submodel_name,
        "assetInformation": {
            "assetKind": "INSTANCE",
            "globalAssetId": submodel_id
        }
    }
    endpoint = f"{basyx_url}/submodels"
    response = requests.post(endpoint, json=payload)
    response.raise_for_status()
    print(f"Created submodel: {submodel_id}")

    # Attach submodel to shell
    encoded_shell = encode_id(thing_id)
    reference_payload = {
        "type": "EXTERNAL_REFERENCE",
        "keys": [{"type": "Submodel", "value": submodel_id}]
    }
    shell_link_url = f"{basyx_url}/shells/{encoded_shell}/submodel-refs"
    link_response = requests.post(shell_link_url, json=reference_payload)
    link_response.raise_for_status()
    print(f"Attached submodel {submodel_id} to shell {thing_id}")


def get_ditto_features(thing_id: str, source_url: str, headers: Dict[str, str]):
    """Get features from a specific Ditto source."""
    url = f"{source_url}/things/{thing_id}/features"
    response = requests.get(url, headers=headers)
    response.raise_for_status()
    return response.json()


def update_basyx_element(submodel_id: str, element_id: str, value, basyx_url: str):
    """Update or create element in BaSyx submodel."""
    encoded_submodel = encode_id(submodel_id)
    
    # Check if element exists
    check_url = f"{basyx_url}/submodels/{encoded_submodel}/submodel-elements/{element_id}"
    response = requests.get(check_url)
    
    if response.status_code == 404:
        # Create element
        create_url = f"{basyx_url}/submodels/{encoded_submodel}/submodel-elements"
        payload = {
            "idShort": element_id,
            "modelType": "Property",
            "valueType": "string",
            "value": str(value)
        }
        requests.post(create_url, json=payload).raise_for_status()
        print(f"Created element {element_id} in {submodel_id}")
    else:
        # Update existing element
        update_url = f"{basyx_url}/submodels/{encoded_submodel}/submodel-elements/{element_id}/$value"
        requests.patch(update_url, json=f'"{value}"').raise_for_status()
        print(f"Updated {element_id} in {submodel_id}")

# Main synchronization loop
if __name__ == "__main__":
    while True:
        config = load_config()
        print("Starting export loop...")
        
        # Always run cleanup to keep BaSyx in sync with configuration
        cleanup_basyx_resources(config)
        
        # Get BaSyx URL from config
        basyx_config = config.get("basyx", {})
        basyx_url = basyx_config.get("url")
        if not basyx_url:
            print("Error: BaSyx URL not configured. Skipping export.")
            print(f"Sleeping {POLL_INTERVAL}s...")
            time.sleep(POLL_INTERVAL)
            continue
            
        sources_config = config.get("data_to_export", {}).get("sources", [])
        
        # Process each source
        for source_config in sources_config:
            source_url = source_config.get("source_url", "")
            source_name = source_config.get("source_name", "unnamed_source")
            auth_header = source_config.get("auth_header", "")
            headers = {"Authorization": auth_header} if auth_header else {}
            
            print(f"Processing source: {source_name} ({source_url})")
            
            try:
                things = get_ditto_things(source_url, headers)
                for thing in things:
                    thing_id = thing.get("thingId")
                    
                    # Get features and check which ones to export
                    features = get_ditto_features(thing_id, source_url, headers)
                    thing_has_exports = False
                    # downtime_start = datetime.fromisoformat(thing.get("downtime", {}).get("start"))
                    # downtime_end = datetime.fromisoformat(thing.get("downtime", {}).get("end"))
                    # time_now = datetime.now()
                    # if downtime_start <= time_now <= downtime_end:
                    #     print(f"Skipping thing {thing_id} due to downtime from {downtime_start} to {downtime_end}")
                    #     continue
                    for feature_id, feature_details in features.items():
                        # Check if this feature should be exported
                        properties_to_export = get_export_config(source_url, thing_id, feature_id, config)
                        if properties_to_export is None:
                            continue  # Skip this feature
                        
                        if not thing_has_exports:
                            # First feature to export - ensure shell exists
                            print(f"Processing thing: {thing_id}")
                            if not get_basyx_shell(thing_id, basyx_url):
                                create_basyx_shell(thing_id, basyx_url)
                            thing_has_exports = True
                        
                        submodel_id = f"{thing_id}:{feature_id}"
                        
                        # Create submodel if not present
                        if not get_basyx_submodel(submodel_id, basyx_url):
                            create_basyx_submodel(thing_id, submodel_id, basyx_url)
                        
                        print(f"Processing feature: {feature_id}")
                        
                        # Verify signature before processing properties
                        signature_verification_enabled = config.get("security", {}).get("verify_signatures", False)
                        if signature_verification_enabled:
                            public_key_path = config.get("security", {}).get("public_key_path", "public_key.pem")
                            if not verify_feature_signature(feature_details, public_key_path):
                                print(f"Warning: Skipping feature {feature_id} of thing {thing_id} due to signature verification failure")
                                continue
                        
                        # Filter properties based on configuration
                        all_properties = feature_details.get("properties", {})
                        if "*" in properties_to_export:
                            filtered_properties = all_properties
                        else:
                            filtered_properties = {
                                key: value for key, value in all_properties.items() 
                                if key in properties_to_export
                            }
                        
                        # Update elements in BaSyx
                        for key, value in filtered_properties.items():
                            update_basyx_element(submodel_id, key, value, basyx_url)
                            
                        # Log filtered properties if configured
                        if config.get("logging", {}).get("log_filtered_items", False):
                            filtered_out = set(all_properties.keys()) - set(filtered_properties.keys())
                            if filtered_out:
                                print(f"Filtered out: {list(filtered_out)} from {feature_id}")
                                
            except Exception as e:
                print(f"Error processing source {source_name}: {e}")
                print("Continuing with next source...")

        print(f"Loop completed. Sleeping {POLL_INTERVAL}s...")
        time.sleep(POLL_INTERVAL)
        