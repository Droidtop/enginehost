#!/usr/bin/env python3
"""Verify one Enginehost V1 archive without installing or executing it.

Only the format-v1 signature (`enginehost-bundle.jws`) is verified. An archive
published with the legacy raw-key signature as well carries
`enginehost-bundle.sig` before the JWS; it is passed over and never trusted
(Droidtop/tracker#768). One that carries only the legacy signature is refused.
"""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import tarfile

import bundle_jws


def fail(message: str) -> None:
    raise SystemExit(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def safe_name(name: str) -> bool:
    path = PurePosixPath(name)
    return bool(name) and not path.is_absolute() and ".." not in path.parts and "\\" not in name


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--root-key", type=Path,
                        help="The Enginehost signing root's public key (PEM PUBLIC KEY) the JWS chain must end at; "
                             "without it the archive's structure and payload are checked, not who signed it")
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
        # Then the v1 signature, after an old archive's legacy one, which is passed over.
        position = 1
        if len(regular) > position and regular[position].name == "enginehost-bundle.sig":
            position += 1
        if len(regular) <= position or regular[position].name != "enginehost-bundle.jws":
            fail("the bundle carries no v1 signature (enginehost-bundle.jws after the manifest)")
        jws_member = regular[position]
        position += 1
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
        jws = read(jws_member).decode("ascii").strip()
        if args.root_key:
            root = bundle_jws.pem_public_key(args.root_key.read_text(encoding="utf-8"))
            try:
                named = bundle_jws.verify(jws, "plugin", root, manifest_bytes)
            except bundle_jws.Refused as error:
                fail("v1 signature refused: " + str(error))
            if not named["developer"] and bundle_jws.origin_id(manifest.get("origin", "")) not in named["origins"]:
                fail("the v1 signing certificate does not name the manifest's origin")
            fingerprint = sha256(named["leafSpki"])

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
