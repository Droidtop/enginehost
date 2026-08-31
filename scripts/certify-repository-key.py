#!/usr/bin/env python3
"""Certify one repository bundle key with the offline Enginehost official root."""

import argparse
import base64
import hashlib
import json
import subprocess
import tempfile
from pathlib import Path


def canonical(document: dict) -> bytes:
    return (document["origin"] + "\n" + document["algorithm"] + "\n" +
            document["publicKeySpki"] + "\n" + document["keySha256"] + "\n").encode()


def public_key(private_key: Path) -> bytes:
    return subprocess.run(
        ["openssl", "pkey", "-in", str(private_key), "-pubout", "-outform", "DER"],
        check=True, stdout=subprocess.PIPE,
    ).stdout


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--official-private-key", required=True, type=Path)
    parser.add_argument("--official-key-id", default="enginehost-official-v1")
    parser.add_argument("--repository-key-document", required=True, type=Path)
    parser.add_argument("--official-public-document", type=Path)
    args = parser.parse_args()
    document = json.loads(args.repository_key_document.read_text(encoding="utf-8"))
    payload = canonical(document)
    official_der = public_key(args.official_private_key)
    official_fingerprint = hashlib.sha256(official_der).hexdigest().upper()
    with tempfile.TemporaryDirectory() as temporary:
        payload_path = Path(temporary) / "repository-key.identity"
        signature_path = Path(temporary) / "repository-key.sig"
        payload_path.write_bytes(payload)
        subprocess.run([
            "openssl", "dgst", "-sha256", "-sign", str(args.official_private_key),
            "-out", str(signature_path), str(payload_path),
        ], check=True)
        signature = signature_path.read_bytes()
    document["issuer"] = {
        "id": args.official_key_id,
        "algorithm": "SHA256withECDSA",
        "keySha256": official_fingerprint,
        "signature": base64.b64encode(signature).decode("ascii"),
    }
    args.repository_key_document.write_text(
        json.dumps(document, sort_keys=True, indent=2) + "\n", encoding="utf-8"
    )
    if args.official_public_document:
        root = {
            "formatVersion": 1,
            "id": args.official_key_id,
            "algorithm": "SHA256withECDSA",
            "publicKeySpki": base64.b64encode(official_der).decode("ascii"),
            "keySha256": official_fingerprint,
        }
        args.official_public_document.write_text(
            json.dumps(root, sort_keys=True, indent=2) + "\n", encoding="utf-8"
        )


if __name__ == "__main__":
    main()
