#!/usr/bin/env python3
"""Certify one repository bundle key with the offline Enginehost official root.

A repository's first key is certified with serial 0, in the original
certificate format every Enginehost build reads. A replacement key (derived
with derive-official-key.py --generation N) is certified with --serial N: the
serial is then part of what the root signs (`enginehost-origin-key-v2`), and
Enginehost and the plugins index accept a new key for an origin only with a
higher serial than the one they hold (docs/security/2026-09-25-third-party-catalog.md, T7, T10).

Either certify an existing enginehost-public-key.json in place
(--repository-key-document), or write a fresh one for a new origin from its
private key (--repository-private-key and --origin, with
--repository-key-document naming the file to write).
"""

import argparse
import base64
import hashlib
import json
import subprocess
import tempfile
from pathlib import Path

V2_PREFIX = "enginehost-origin-key-v2"


def canonical(document: dict, serial: int) -> bytes:
    """What the root signs. Serial 0 is the original (v1) identity, byte for byte."""
    identity = (document["origin"] + "\n" + document["algorithm"] + "\n" +
                document["publicKeySpki"] + "\n" + document["keySha256"] + "\n")
    if serial:
        identity = V2_PREFIX + "\n" + identity + str(serial) + "\n"
    return identity.encode()


def public_key(private_key: Path) -> bytes:
    return subprocess.run(
        ["openssl", "pkey", "-in", str(private_key), "-pubout", "-outform", "DER"],
        check=True, stdout=subprocess.PIPE,
    ).stdout


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--official-private-key", required=True, type=Path)
    parser.add_argument("--official-key-id", default="enginehost-official-v1")
    parser.add_argument("--repository-key-document", required=True, type=Path)
    parser.add_argument("--repository-private-key", type=Path,
                        help="write a fresh key document for this key instead of certifying an existing one")
    parser.add_argument("--origin", help="the repository origin, with --repository-private-key")
    parser.add_argument("--serial", type=int, default=0,
                        help="0 for an origin's first key; the generation for a replacement")
    parser.add_argument("--official-public-document", type=Path)
    args = parser.parse_args()
    if args.serial < 0:
        raise SystemExit("--serial is never negative")
    if args.repository_private_key:
        if not args.origin:
            raise SystemExit("--repository-private-key needs --origin")
        origin = args.origin.rstrip("/").removesuffix(".git").lower()
        if not origin.startswith("https://github.com/"):
            raise SystemExit("--origin must be a canonical GitHub URL")
        der = public_key(args.repository_private_key)
        document = {
            "formatVersion": 1,
            "origin": origin,
            "algorithm": "SHA256withECDSA",
            "publicKeySpki": base64.b64encode(der).decode("ascii"),
            "keySha256": hashlib.sha256(der).hexdigest().upper(),
        }
    else:
        document = json.loads(args.repository_key_document.read_text(encoding="utf-8"))
        document.pop("issuer", None)
    payload = canonical(document, args.serial)
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
    if args.serial:
        document["issuer"]["serial"] = args.serial
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
