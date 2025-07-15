import base64
import json
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

def sign_json_payload(payload: dict, private_key_path: str = "private_key.pem" ) -> bytes:
    """Signs a JSON payload using the provided private key file (PEM format)."""
    # Serialize JSON consistently
    payload_bytes = json.dumps(payload, sort_keys=True).encode('utf-8')

    # Load private key
    with open(private_key_path, "rb") as key_file:
        private_key = serialization.load_pem_private_key(
            key_file.read(),
            password=None,
        )

    # Create signature
    signature = private_key.sign(
        payload_bytes,
        padding.PSS(
            mgf=padding.MGF1(hashes.SHA256()),
            salt_length=padding.PSS.MAX_LENGTH
        ),
        hashes.SHA256()
    )
    return base64.b64encode(signature).decode('utf-8')