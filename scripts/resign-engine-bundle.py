#!/usr/bin/env python3
"""Sign an already published, legacy-signed engine bundle in format v1.

The owner's decision (2026-10-16, Droidtop/tracker#550): the September stable
releases carry only the legacy signature, which Enginehost no longer accepts
for an install, so each is re-signed and published again as a new stable
release; the published one stays as it is. Re-signing changes no code: the
manifest bytes, and with them every payload file's digest, are exactly the
ones the legacy signature covers. What is written is `enginehost-bundle.jws`, a
detached JWS over those same bytes made with the repository's v1 signing key
and its committed certificate, right after the manifest as
build-engine-bundle.py places it. The legacy `enginehost-bundle.sig` is left
out: no Enginehost verifies it (Droidtop/tracker#768), and the manifest's own
`signing` block, which the signed bytes still carry, is passed over. Every
other member is written back unchanged, in order, with build-engine-bundle.py's
own member metadata.

    resign-engine-bundle.py SOURCE.enginehost.tar.xz --certificate-key KEY.pem \
        --certificate-chain enginehost-signing-certificate.pem --output DIR \
        [--release-envelope enginehost-release.json]

The output is DIR/<the manifest's assetName>; with --release-envelope, the
envelope's entry for this manifest gains the same `jws`, loses its legacy
`signatureBase64`, and is written to DIR.
A bundle that already carries a v1 signature is refused, not signed twice.

With --only-64-bit VERSION (owner, 2026-10-30, Droidtop/tracker#646: "strip it
out"), the bundle is re-signed without its 32-bit libraries instead: every
lib/<abi>/ outside arm64-v8a and x86_64 is left out, of the bundle and of each
resource APK it lists, and the manifest is written again with the files that
remain, their digests, the payload digest and pluginVersion VERSION, which must
be newer than the bundle's (the catalog offers a line's newest build). The
64-bit code is byte for byte the one published; only the manifest, the resource
APKs that held 32-bit libraries and the signature change. A bundle that already
carries v1 may be signed this way, since its manifest changes.
"""

import argparse
import base64
import hashlib
import importlib.util
import io
import json
import re
import sys
import tarfile
import zipfile
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))
import bundle_jws  # noqa: E402

_spec = importlib.util.spec_from_file_location("build_engine_bundle", SCRIPTS / "build-engine-bundle.py")
build_engine_bundle = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(build_engine_bundle)
_conformance_spec = importlib.util.spec_from_file_location("check_bundle_conformance", SCRIPTS / "check-bundle-conformance.py")
check_bundle_conformance = importlib.util.module_from_spec(_conformance_spec)
_conformance_spec.loader.exec_module(check_bundle_conformance)
SHIPPED_ABIS = check_bundle_conformance.REQUIRED_ABIS

MANIFEST = "enginehost-bundle.json"
LEGACY = "enginehost-bundle.sig"
JWS = "enginehost-bundle.jws"


def read_members(path: Path) -> list[tuple[tarfile.TarInfo, bytes | None]]:
    """Every member in order, with its bytes (None for a directory)."""
    members = []
    with tarfile.open(path, "r:xz") as archive:
        for info in archive.getmembers():
            if info.isdir():
                members.append((info, None))
            elif info.isreg():
                members.append((info, archive.extractfile(info).read()))
            else:
                raise SystemExit(f"{path.name}: refusing a {info.type!r} member {info.name}")
    return members


def version_key(version: str) -> tuple[int, ...]:
    """"X.Y.Z-N" as numbers; the older "X.Y.<run>" reads as X.Y.0-<run>, as the app reads it."""
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:-(\d+))?", version)
    if not match:
        raise SystemExit(f"{version!r} is not a plugin version (X.Y.Z-N)")
    major, minor, patch, build = match.groups()
    if build is None:
        return int(major), int(minor), 0, int(patch)
    return int(major), int(minor), int(patch), int(build)


def other_abi(name: str) -> bool:
    """A path under lib/<abi>/ for an ABI that is not shipped."""
    parts = name.split("/")
    return len(parts) > 2 and parts[0] == "lib" and parts[1] not in SHIPPED_ABIS


def without_other_abis(apk: bytes) -> bytes:
    """The zip without its lib/<32-bit>/ entries, every other entry in order and as
    stored. A stored entry stays aligned as zipalign leaves it, 4 bytes (4096 for a
    library), so nothing that maps it from the zip moves off its boundary."""
    source = zipfile.ZipFile(io.BytesIO(apk))
    if not any(other_abi(info.filename) for info in source.infolist()):
        return apk
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as target:
        for info in source.infolist():
            if other_abi(info.filename):
                continue
            data = source.read(info)
            copy = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            copy.compress_type = info.compress_type
            copy.external_attr = info.external_attr
            copy.create_system = info.create_system
            if info.compress_type == zipfile.ZIP_STORED:
                align = 4096 if info.filename.endswith(".so") else 4
                start = out.tell() + 30 + len(info.filename.encode())
                copy.extra = b"\0" * ((align - start % align) % align)
            target.writestr(copy, data)
    return out.getvalue()


def only_64_bit(members, manifest: dict, version: str):
    """The members and manifest without 32-bit libraries, at pluginVersion version."""
    if version_key(version) <= version_key(str(manifest.get("pluginVersion", "0.0.0"))):
        raise SystemExit(f"{version} is not newer than the bundle's {manifest.get('pluginVersion')}")
    apks = set(manifest.get("resourceApks") or [])
    modes = {entry["path"]: entry.get("mode", 0o644) for entry in manifest.get("files", [])}
    kept, records = [], []
    aggregate = hashlib.sha256()
    for info, data in members:
        if info.name in (MANIFEST, LEGACY, JWS):
            continue
        if other_abi(info.name):
            continue
        if data is not None and info.name in apks:
            data = without_other_abis(data)
        kept.append((info, data))
        if data is None:
            continue
        aggregate.update(info.name.encode() + b"\0" + str(len(data)).encode("ascii") + b"\0" + data)
        records.append({"path": info.name, "size": len(data), "sha256": build_engine_bundle.sha256(data),
                        "mode": modes.get(info.name, info.mode)})
    if sorted(record["path"] for record in records) != [record["path"] for record in records]:
        raise SystemExit("the bundle's payload is not in path order")
    rewritten = dict(manifest)
    rewritten.pop("signing", None)
    rewritten.update({"pluginVersion": version, "payloadSha256": aggregate.hexdigest().upper(), "files": records})
    return kept, rewritten


def resign(source: Path, key: Path, chain_path: Path, output_dir: Path, envelope: Path | None,
           only_64_bit_version: str | None = None) -> Path:
    members = read_members(source)
    names = [info.name for info, _ in members]
    if not names or names[0] != MANIFEST:
        raise SystemExit(f"{source.name}: the first member is not {MANIFEST}")
    if JWS in names and only_64_bit_version is None:
        raise SystemExit(f"{source.name} already carries a v1 signature")
    original_bytes = manifest_bytes = members[0][1]
    manifest = json.loads(manifest_bytes)
    payload = None
    if only_64_bit_version is not None:
        payload, manifest = only_64_bit(members, manifest, only_64_bit_version)
        manifest_bytes = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
    asset_name = manifest.get("assetName", "")
    if not asset_name.endswith(".enginehost.tar.xz") or "/" in asset_name:
        raise SystemExit(f"{source.name}: the manifest's assetName {asset_name!r} is not a bundle file name")

    chain = bundle_jws.pem_certificates(chain_path.read_text(encoding="utf-8"))
    origin = str(manifest.get("origin", "")).rstrip("/").removesuffix(".git")
    if bundle_jws.ORIGIN_URN + bundle_jws.origin_id(origin) not in (name.lower() for name in bundle_jws.uris(chain[0])):
        raise SystemExit(f"the signing certificate does not name {origin}")
    if bundle_jws.PURPOSES["plugin"][0] not in bundle_jws.certificate_text(chain[0], "extendedKeyUsage"):
        raise SystemExit("the signing certificate is not for plugin bundles")
    jws = bundle_jws.sign(manifest_bytes, key, chain, "plugin", detached=True)

    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / asset_name
    with tarfile.open(output, "w:xz", format=tarfile.USTAR_FORMAT) as archive:
        if payload is not None:
            # The order build-engine-bundle.py writes: manifest, signature, payload.
            archive.addfile(*build_engine_bundle.tar_member(MANIFEST, manifest_bytes))
            archive.addfile(*build_engine_bundle.tar_member(JWS, jws.encode("ascii") + b"\n"))
            members = []
            for info, data in payload:
                if data is None:
                    archive.addfile(info)
                else:
                    archive.addfile(*build_engine_bundle.tar_member(info.name, data, info.mode))
        for index, (info, data) in enumerate(members):
            if info.name == LEGACY:
                continue
            if data is None:
                archive.addfile(info)
            else:
                archive.addfile(*build_engine_bundle.tar_member(info.name, data, info.mode))
            if index == 0:
                archive.addfile(*build_engine_bundle.tar_member(JWS, jws.encode("ascii") + b"\n"))

    if envelope is not None:
        data = json.loads(envelope.read_text(encoding="utf-8"))
        encoded = base64.b64encode(original_bytes).decode("ascii")
        entries = [entry for entry in data.get("bundles", []) if entry.get("manifestBase64") == encoded]
        if len(entries) != 1:
            raise SystemExit(f"{envelope.name} has no single entry for this bundle's manifest")
        entries[0]["manifestBase64"] = base64.b64encode(manifest_bytes).decode("ascii")
        entries[0]["jws"] = jws
        entries[0].pop("signatureBase64", None)
        (output_dir / envelope.name).write_text(
            json.dumps(data, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8"
        )
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--certificate-key", required=True, type=Path)
    parser.add_argument("--certificate-chain", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, help="Directory for the re-signed bundle (and envelope)")
    parser.add_argument("--release-envelope", type=Path)
    parser.add_argument("--only-64-bit", metavar="VERSION",
                        help="Leave out every 32-bit library and publish as pluginVersion VERSION (newer than the bundle's)")
    args = parser.parse_args()
    print(resign(args.bundle, args.certificate_key, args.certificate_chain, args.output, args.release_envelope,
                 args.only_64_bit))


if __name__ == "__main__":
    main()
