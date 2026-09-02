#!/usr/bin/env python3
"""Derive recoverable Enginehost P-256 root/repository keys from one master seed."""

import argparse
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


P256_ORDER = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
SALT = b"enginehost-official-master-v1"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--master-seed", required=True, type=Path)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--official-root", action="store_true")
    group.add_argument("--repository-origin")
    group.add_argument("--application-id")
    group.add_argument("--developer-debug", action="store_true")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    seed = args.master_seed.read_bytes()
    if len(seed) != 32:
        raise SystemExit("Official master seed must contain exactly 32 bytes")
    if args.official_root:
        info = b"enginehost/official-root-signing/v1"
    elif args.repository_origin:
        origin = args.repository_origin.rstrip("/").removesuffix(".git").lower()
        if not origin.startswith("https://github.com/"):
            raise SystemExit("Repository origin must be a canonical GitHub URL")
        info = b"enginehost/repository-bundle-signing/v1\0" + origin.encode()
    elif args.developer_debug:
        # The primary developer's own key. It is deliberately NOT origin-scoped:
        # it exists to sign locally rebuilt bundles for any repository during
        # development. Enginehost accepts it for any origin and then marks the
        # plugin Ultimate: the strongest proof of origin the system has, and
        # separately never mistakable for an official release, since being a
        # production release is a statement about the release path, not the key.
        info = b"enginehost/primary-developer-debug-signing/v1"
    else:
        application_id = args.application_id.strip().lower()
        if not application_id or "." not in application_id:
            raise SystemExit("Application ID must be a dotted Android package name")
        info = b"enginehost/android-apk-signing/v1\0" + application_id.encode()
    material = HKDF(algorithm=hashes.SHA256(), length=48, salt=SALT, info=info).derive(seed)
    scalar = int.from_bytes(material, "big") % (P256_ORDER - 1) + 1
    key = ec.derive_private_key(scalar, ec.SECP256R1())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ))


if __name__ == "__main__":
    main()
