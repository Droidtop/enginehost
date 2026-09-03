#!/usr/bin/env python3
"""Build Enginehost bundle-format v1 with an internal ECDSA P-256 signature."""

import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import stat
import subprocess
import tarfile
import tempfile


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def run_openssl(*arguments: str) -> bytes:
    process = subprocess.run(
        ["openssl", *arguments], check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    if process.returncode:
        raise SystemExit(process.stderr.decode(errors="replace").strip())
    return process.stdout


def payload_files(root: Path):
    result = []
    for path in sorted(root.rglob("*"), key=lambda item: item.as_posix()):
        if path.is_symlink():
            raise SystemExit(f"payload links are forbidden: {path}")
        if path.is_dir():
            continue
        if not path.is_file():
            raise SystemExit(f"unsupported payload entry: {path}")
        relative = path.relative_to(root).as_posix()
        if PurePosixPath(relative).is_absolute() or ".." in PurePosixPath(relative).parts:
            raise SystemExit(f"unsafe payload path: {relative}")
        data = path.read_bytes()
        mode = stat.S_IMODE(path.stat().st_mode) & 0o777
        result.append((relative, path, data, mode))
    if not result:
        raise SystemExit("payload directory is empty")
    return result


def public_key(private_key: Path) -> bytes:
    der = run_openssl("pkey", "-in", str(private_key), "-pubout", "-outform", "DER")
    # OpenSSL's explicit curve check works with the private key and avoids
    # accidentally publishing a validly signed bundle with a different curve.
    details = run_openssl("pkey", "-in", str(private_key), "-text", "-noout").decode(errors="replace")
    if "prime256v1" not in details and "P-256" not in details:
        raise SystemExit("the signing key must use ECDSA P-256 (prime256v1)")
    return der


def tar_member(name: str, data: bytes, mode: int = 0o444) -> tuple[tarfile.TarInfo, io.BytesIO]:
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mode = mode
    info.mtime = 0
    info.uid = info.gid = 0
    info.uname = info.gname = ""
    return info, io.BytesIO(data)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--metadata", required=True, type=Path)
    parser.add_argument("--payload", required=True, type=Path)
    parser.add_argument("--private-key", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--release-envelope", type=Path)
    parser.add_argument("--public-key-document", type=Path)
    parser.add_argument("--build-number", type=int,
                        help="CI build counter; defaults to GITHUB_RUN_NUMBER when set. "
                             "Becomes pluginVersion's third component so every build of a line "
                             "is a strictly newer build than the one before it.")
    parser.add_argument("--repository-key-document", type=Path,
                        help="Existing certified repository key document to validate and copy")
    args = parser.parse_args()
    if not args.output.name.endswith(".enginehost.tar.xz"):
        raise SystemExit("output name must end in .enginehost.tar.xz")

    metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
    required = {
        "bundleId", "engine", "pluginVersion", "apiVersion", "entrypoint",
        "origin", "dexFiles", "capabilities",
    }
    missing = sorted(required - metadata.keys())
    if missing:
        raise SystemExit("metadata is missing: " + ", ".join(missing))

    # pluginVersion is the total order on builds within a bundle id (see
    # docs/engine-bundle-format.md), yet the value in a repository's metadata
    # is hand-written and rarely touched: every CI build of a line carried the
    # same "1.0.0", so no build was ever a newer build of another and the
    # in-app update check had nothing to offer. The CI run counter fixes the
    # third component: major.minor stay the maintainer's statement of intent,
    # the patch component becomes "which build", monotonic per repository.
    build_number = args.build_number
    if build_number is None and os.environ.get("GITHUB_RUN_NUMBER", "").isdigit():
        build_number = int(os.environ["GITHUB_RUN_NUMBER"])
    if build_number is not None:
        declared = [int(part) for part in str(metadata["pluginVersion"]).split(".")]
        major_minor = (declared + [0, 0])[:2]
        metadata["pluginVersion"] = ".".join(str(part) for part in major_minor + [build_number])

    files = payload_files(args.payload)
    aggregate = hashlib.sha256()
    records = []
    for relative, _path, data, mode in files:
        encoded_path = relative.encode()
        aggregate.update(encoded_path)
        aggregate.update(b"\0")
        aggregate.update(str(len(data)).encode("ascii"))
        aggregate.update(b"\0")
        aggregate.update(data)
        records.append({"path": relative, "size": len(data), "sha256": sha256(data), "mode": mode})

    public_der = public_key(args.private_key)
    origin = metadata["origin"].rstrip("/").removesuffix(".git")
    manifest = dict(metadata)
    manifest.update({
        "formatVersion": 1,
        "assetName": args.output.name,
        "origin": origin,
        "signing": {
            "algorithm": "SHA256withECDSA",
            "publicKeySpki": base64.b64encode(public_der).decode("ascii"),
            "keySha256": sha256(public_der),
        },
        "payloadSha256": aggregate.hexdigest().upper(),
        "files": records,
    })
    manifest_bytes = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
    with tempfile.TemporaryDirectory() as temporary:
        manifest_path = Path(temporary) / "enginehost-bundle.json"
        signature_path = Path(temporary) / "enginehost-bundle.sig.der"
        manifest_path.write_bytes(manifest_bytes)
        subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", str(args.private_key),
             "-out", str(signature_path), str(manifest_path)],
            check=True,
        )
        signature = signature_path.read_bytes()

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(args.output, "w:xz", format=tarfile.USTAR_FORMAT) as archive:
        archive.addfile(*tar_member("enginehost-bundle.json", manifest_bytes))
        archive.addfile(*tar_member("enginehost-bundle.sig", base64.b64encode(signature) + b"\n"))
        for relative, _path, data, mode in files:
            archive.addfile(*tar_member(relative, data, mode))

    if args.release_envelope:
        envelope = {
            "formatVersion": 1,
            "bundles": [{
                "manifestBase64": base64.b64encode(manifest_bytes).decode("ascii"),
                "signatureBase64": base64.b64encode(signature).decode("ascii"),
            }],
        }
        args.release_envelope.write_text(
            json.dumps(envelope, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8"
        )

    if args.public_key_document:
        key_document = {
            "formatVersion": 1,
            "origin": origin,
            "algorithm": "SHA256withECDSA",
            "publicKeySpki": base64.b64encode(public_der).decode("ascii"),
            "keySha256": sha256(public_der),
        }
        if args.repository_key_document:
            certified = json.loads(args.repository_key_document.read_text(encoding="utf-8"))
            for name, value in key_document.items():
                if certified.get(name) != value:
                    raise SystemExit(f"Certified repository key mismatch: {name}")
            key_document = certified
        args.public_key_document.write_text(
            json.dumps(key_document, sort_keys=True, indent=2) + "\n", encoding="utf-8"
        )


if __name__ == "__main__":
    main()
