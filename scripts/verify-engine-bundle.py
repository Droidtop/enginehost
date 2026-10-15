#!/usr/bin/env python3
"""Verify one Enginehost V1 archive without installing or executing it."""

import argparse
import base64
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import tarfile
import tempfile

import bundle_jws


def fail(message: str) -> None:
    raise SystemExit(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def safe_name(name: str) -> bool:
    path = PurePosixPath(name)
    return bool(name) and not path.is_absolute() and ".." not in path.parts and "\\" not in name


def normalized_origin(origin: str) -> str:
    return origin.rstrip("/").removesuffix(".git").lower()


def verify_signature(manifest: bytes, signature: bytes, public_der: bytes) -> None:
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        manifest_path = root / "manifest.json"
        signature_path = root / "signature.der"
        public_path = root / "public.der"
        manifest_path.write_bytes(manifest)
        signature_path.write_bytes(signature)
        public_path.write_bytes(public_der)
        result = subprocess.run(
            [
                "openssl", "dgst", "-sha256", "-verify", str(public_path),
                "-keyform", "DER", "-signature", str(signature_path), str(manifest_path),
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if result.returncode:
            fail("manifest signature verification failed: " + result.stderr.decode(errors="replace").strip())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--repository-key", type=Path,
                        help="Legacy: the repository key document the legacy signature must match")
    parser.add_argument("--core-key", type=Path,
                        help="Format v1: Droidtop Core's public key (PEM PUBLIC KEY) the JWS chain must end at")
    parser.add_argument("--require-v1", action="store_true",
                        help="Refuse a bundle that carries no v1 signature")
    args = parser.parse_args()
    if not args.bundle.is_file() or not args.bundle.name.endswith(".enginehost.tar.xz"):
        fail("bundle must be an existing *.enginehost.tar.xz file")

    with tarfile.open(args.bundle, "r:xz") as archive:
        members = archive.getmembers()
        if any(not safe_name(member.name) for member in members):
            fail("archive contains an unsafe path")
        if any(not (member.isfile() or member.isdir()) for member in members):
            fail("archive contains a link or special entry")
        regular = [member for member in members if member.isfile()]
        if not regular or regular[0].name != "enginehost-bundle.json":
            fail("the signed manifest must be the first regular file")
        # Then the legacy signature, the v1 signature, or both in that order.
        signature_members = {}
        position = 1
        for name in ("enginehost-bundle.sig", "enginehost-bundle.jws"):
            if len(regular) > position and regular[position].name == name:
                signature_members[name] = regular[position]
                position += 1
        if not signature_members:
            fail("the manifest must be followed by enginehost-bundle.sig, enginehost-bundle.jws or both")
        if args.require_v1 and "enginehost-bundle.jws" not in signature_members:
            fail("the bundle carries no v1 signature")
        if len({member.name for member in regular}) != len(regular):
            fail("archive contains duplicate regular-file paths")

        def read(member: tarfile.TarInfo) -> bytes:
            source = archive.extractfile(member)
            if source is None:
                fail("could not read " + member.name)
            return source.read()

        manifest_bytes = read(regular[0])
        try:
            manifest = json.loads(manifest_bytes)
        except (ValueError, json.JSONDecodeError) as error:
            fail("invalid signed metadata: " + str(error))
        if manifest.get("formatVersion") != 1:
            fail("unsupported bundle format")
        if manifest.get("assetName") != args.bundle.name:
            fail("manifest assetName does not match archive filename")

        fingerprint = None
        if "enginehost-bundle.sig" in signature_members:
            try:
                signature = base64.b64decode(read(signature_members["enginehost-bundle.sig"]).strip(), validate=True)
                signing = manifest["signing"]
                public_der = base64.b64decode(signing["publicKeySpki"], validate=True)
            except (KeyError, ValueError) as error:
                fail("invalid legacy signature: " + str(error))
            if signing.get("algorithm") != "SHA256withECDSA":
                fail("unsupported legacy signature format")
            fingerprint = sha256(public_der)
            if signing.get("keySha256", "").upper() != fingerprint:
                fail("embedded public-key fingerprint mismatch")
            verify_signature(manifest_bytes, signature, public_der)
        elif "signing" in manifest:
            fail("a manifest with a legacy signing block must carry the legacy signature")

        if "enginehost-bundle.jws" in signature_members:
            jws = read(signature_members["enginehost-bundle.jws"]).decode("ascii").strip()
            if args.core_key:
                core = bundle_jws.pem_public_key(args.core_key.read_text(encoding="utf-8"))
                try:
                    named = bundle_jws.verify(jws, "plugin", core, manifest_bytes)
                except bundle_jws.Refused as error:
                    fail("v1 signature refused: " + str(error))
                if not named["developer"] and bundle_jws.origin_id(manifest.get("origin", "")) not in named["origins"]:
                    fail("the v1 signing certificate does not name the manifest's origin")
                fingerprint = sha256(named["leafSpki"])
            elif args.require_v1:
                fail("--require-v1 needs --core-key to check the v1 signature against")

        if args.repository_key:
            if fingerprint is None or "enginehost-bundle.sig" not in signature_members:
                fail("--repository-key checks the legacy signature, which this bundle does not carry")
            key = json.loads(args.repository_key.read_text(encoding="utf-8"))
            if normalized_origin(key.get("origin", "")) != normalized_origin(manifest.get("origin", "")):
                fail("repository-key origin mismatch")
            if key.get("keySha256", "").upper() != fingerprint:
                fail("bundle signer does not match repository key")
            if base64.b64decode(key.get("publicKeySpki", ""), validate=True) != public_der:
                fail("repository public key does not match bundle signer")

        records = manifest.get("files")
        if not isinstance(records, list) or not records:
            fail("manifest payload file list is missing or empty")
        payload_members = regular[position:]
        if [record.get("path") for record in records] != [member.name for member in payload_members]:
            fail("payload order or path does not match the signed manifest")

        aggregate = hashlib.sha256()
        for record, member in zip(records, payload_members):
            data = read(member)
            path = record.get("path", "")
            if not safe_name(path):
                fail("manifest contains an unsafe payload path")
            if record.get("size") != len(data) or record.get("sha256", "").upper() != sha256(data):
                fail("payload size or digest mismatch: " + path)
            if record.get("mode") != member.mode & 0o777:
                fail("payload mode mismatch: " + path)
            aggregate.update(path.encode("utf-8"))
            aggregate.update(b"\0")
            aggregate.update(str(len(data)).encode("ascii"))
            aggregate.update(b"\0")
            aggregate.update(data)
        if manifest.get("payloadSha256", "").upper() != aggregate.hexdigest().upper():
            fail("aggregate payload digest mismatch")

    print(json.dumps({
        "bundle": str(args.bundle),
        "bundleId": manifest.get("bundleId"),
        "engine": manifest.get("engine"),
        "pluginVersion": manifest.get("pluginVersion"),
        "signingKeySha256": fingerprint,
        "payloadFiles": len(records),
        "verified": True,
    }, sort_keys=True))


if __name__ == "__main__":
    main()
