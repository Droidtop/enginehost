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
"""

import argparse
import base64
import importlib.util
import json
import sys
import tarfile
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))
import bundle_jws  # noqa: E402

_spec = importlib.util.spec_from_file_location("build_engine_bundle", SCRIPTS / "build-engine-bundle.py")
build_engine_bundle = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(build_engine_bundle)

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


def resign(source: Path, key: Path, chain_path: Path, output_dir: Path, envelope: Path | None) -> Path:
    members = read_members(source)
    names = [info.name for info, _ in members]
    if not names or names[0] != MANIFEST:
        raise SystemExit(f"{source.name}: the first member is not {MANIFEST}")
    if JWS in names:
        raise SystemExit(f"{source.name} already carries a v1 signature")
    manifest_bytes = members[0][1]
    manifest = json.loads(manifest_bytes)
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
        encoded = base64.b64encode(manifest_bytes).decode("ascii")
        entries = [entry for entry in data.get("bundles", []) if entry.get("manifestBase64") == encoded]
        if len(entries) != 1:
            raise SystemExit(f"{envelope.name} has no single entry for this bundle's manifest")
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
    args = parser.parse_args()
    print(resign(args.bundle, args.certificate_key, args.certificate_chain, args.output, args.release_envelope))


if __name__ == "__main__":
    main()
