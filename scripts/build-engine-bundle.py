#!/usr/bin/env python3
"""Build an Enginehost bundle (format v1) and sign it.

Signature format v1 (Droidtop/tracker#550): `--certificate-key` and
`--certificate-chain` add `enginehost-bundle.jws`, a detached JWS over the
manifest whose x5c chain ends at the Enginehost signing root. The legacy raw-key signature
(`--private-key`, `enginehost-bundle.sig`) is still made when that key is
given, so builds that cannot verify v1 yet still install it; at least one of
the two is required. See docs/engine-bundle-format.md "Signatures".
"""

import argparse
import base64
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import stat
import subprocess
import sys
import tarfile
import tempfile

import bundle_jws
from release_history import COMPONENT_ENVELOPE, next_build, published_versions, version_key


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
    parser.add_argument("--private-key", type=Path,
                        help="Legacy raw P-256 signing key: adds enginehost-bundle.sig and the manifest's signing block")
    parser.add_argument("--certificate-key", type=Path,
                        help="Format v1: the private key of the signing certificate")
    parser.add_argument("--certificate-chain", type=Path,
                        help="Format v1: PEM certificates, the signing certificate first, the root excluded")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--release-envelope", type=Path)
    parser.add_argument("--public-key-document", type=Path)
    parser.add_argument("--release-history-repo", metavar="OWNER/REPO",
                        help="Repository whose published releases decide the build counter. "
                             "pluginVersion becomes <declared>-<N>: N is the highest build "
                             "already published for that declared version on this bundle id, "
                             "plus one (1 for a version not yet published). Needs the gh CLI "
                             "and GH_TOKEN. Without it pluginVersion is left as declared.")
    parser.add_argument("--repository-key-document", type=Path,
                        help="Existing certified repository key document to validate and copy")
    args = parser.parse_args()
    if not args.output.name.endswith(".enginehost.tar.xz"):
        raise SystemExit("output name must end in .enginehost.tar.xz")
    if (args.certificate_key is None) != (args.certificate_chain is None):
        raise SystemExit("--certificate-key and --certificate-chain go together")
    if args.private_key is None and args.certificate_key is None:
        raise SystemExit("sign with --private-key (legacy), --certificate-key/--certificate-chain (v1), or both")
    if args.public_key_document and args.private_key is None:
        raise SystemExit("--public-key-document describes the legacy key; it needs --private-key")

    metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
    # A runtime-component bundle (docs/engine-bundle-format.md, "Runtime
    # components") carries no code the host loads and serves no game itself;
    # what it provides and its licence are required instead.
    kind = metadata.get("kind", "engine")
    if kind == "engine":
        required = {
            "bundleId", "engine", "pluginVersion", "apiVersion", "entrypoint",
            "origin", "dexFiles", "capabilities",
        }
    elif kind == "component":
        required = {"bundleId", "engine", "pluginVersion", "apiVersion", "origin", "component", "license"}
        for forbidden in ("dexFiles", "entrypoint", "capabilities"):
            if metadata.get(forbidden):
                raise SystemExit(f"a component bundle has no {forbidden}")
    else:
        raise SystemExit(f"unknown bundle kind {kind!r}")
    missing = sorted(required - metadata.keys())
    if missing:
        raise SystemExit("metadata is missing: " + ", ".join(missing))
    if kind == "component":
        component = metadata["component"]
        for key in ("engine", "name", "version", "supportedSeries"):
            if not component.get(key):
                raise SystemExit(f"component is missing {key}")
        if component["engine"] != metadata["engine"]:
            raise SystemExit("component.engine must be the bundle's engine")
        licence = metadata["license"]
        if not licence.get("name") or not (args.payload / str(licence.get("file", ""))).is_file():
            raise SystemExit("a component bundle carries its licence: license.name and a payload license.file")

    # pluginVersion is the total order on builds within a bundle id (see
    # docs/engine-bundle-format.md), yet the value in a repository's metadata
    # is hand-written and rarely touched, so no build was ever a newer build
    # of another and the in-app update check had nothing to offer. The
    # maintainer declares X.Y.Z; this appends a per-version build counter
    # (Droidtop/tracker#126) taken from the repository's own releases, so a
    # CI rerun never repeats a number and a new declared version restarts at 1.
    if args.release_history_repo:
        declared = str(metadata["pluginVersion"])
        if "-" in declared:
            raise SystemExit(f"declared pluginVersion must be X.Y.Z, got {declared}")
        published = published_versions(args.release_history_repo, metadata["bundleId"])
        build = next_build(declared, published)
        newest = max(published, key=version_key, default=None)
        metadata["pluginVersion"] = f"{declared}-{build}"
        if newest is not None and version_key(metadata["pluginVersion"]) <= version_key(newest):
            print(f"::warning::{metadata['pluginVersion']} does not order above the already published "
                  f"{newest}; declare a higher version so this build is offered as an update",
                  file=sys.stderr)

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

    origin = metadata["origin"].rstrip("/").removesuffix(".git")
    chain = None
    if args.certificate_chain:
        chain = bundle_jws.pem_certificates(args.certificate_chain.read_text(encoding="utf-8"))
        # The certificate must name this origin and be for plugin bundles,
        # or the app refuses every bundle signed with it; fail here instead.
        names = bundle_jws.uris(chain[0])
        if bundle_jws.ORIGIN_URN + bundle_jws.origin_id(origin) not in (name.lower() for name in names):
            raise SystemExit(f"the signing certificate does not name {origin} (urn:droidtop:origin:{bundle_jws.origin_id(origin)})")
        if bundle_jws.PURPOSES["plugin"][0] not in bundle_jws.certificate_text(chain[0], "extendedKeyUsage"):
            raise SystemExit("the signing certificate is not for plugin bundles")
    manifest = dict(metadata)
    manifest.pop("signing", None)
    manifest.update({
        "formatVersion": 1,
        "assetName": args.output.name,
        "origin": origin,
        "payloadSha256": aggregate.hexdigest().upper(),
        "files": records,
    })
    public_der = None
    if args.private_key:
        public_der = public_key(args.private_key)
        manifest["signing"] = {
            "algorithm": "SHA256withECDSA",
            "publicKeySpki": base64.b64encode(public_der).decode("ascii"),
            "keySha256": sha256(public_der),
        }
    manifest_bytes = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
    signature = None
    if args.private_key:
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
    jws = None
    if chain:
        jws = bundle_jws.sign(manifest_bytes, args.certificate_key, chain, "plugin", detached=True)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(args.output, "w:xz", format=tarfile.USTAR_FORMAT) as archive:
        archive.addfile(*tar_member("enginehost-bundle.json", manifest_bytes))
        # The legacy signature first, then the v1 one: the order the app reads them in.
        if signature is not None:
            archive.addfile(*tar_member("enginehost-bundle.sig", base64.b64encode(signature) + b"\n"))
        if jws is not None:
            archive.addfile(*tar_member("enginehost-bundle.jws", jws.encode("ascii") + b"\n"))
        for relative, _path, data, mode in files:
            archive.addfile(*tar_member(relative, data, mode))

    if args.release_envelope:
        # A component's envelope has its own name, so an Enginehost from
        # before component bundles skips the release instead of failing on
        # it (and with it the repository's whole catalog). The callers pass
        # the engine name; the component one sits beside it.
        if kind == "component":
            args.release_envelope = args.release_envelope.with_name(COMPONENT_ENVELOPE)
        entry = {"manifestBase64": base64.b64encode(manifest_bytes).decode("ascii")}
        if signature is not None:
            entry["signatureBase64"] = base64.b64encode(signature).decode("ascii")
        if jws is not None:
            entry["jws"] = jws
        envelope = {"formatVersion": 1, "bundles": [entry]}
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
