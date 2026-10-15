#!/usr/bin/env python3
"""Issue format-v1 signing certificates under Droidtop Core (Droidtop/tracker#550).

Run by the owner, on the machine holding the Droidtop Core key, never in CI.
Everything it derives is reproducible: re-downloading the Core key from the CA
and running this again yields the same signing keys.

    master seed  = HKDF-SHA256(ikm = Core's raw private value,
                               salt = "droidtop-ca-root-v1",
                               info = "enginehost-plugin-root-master-seed")
    origin key   = derive-official-key.py's repository derivation of that seed
                   (P-256, ES256), for the canonical GitHub origin
    certificate  = issued and signed by the Core key: CA:FALSE, keyUsage
                   digitalSignature, the purpose's extended key usage, and the
                   identity URNs docs/engine-bundle-format.md "Certificates" names

"Core's raw private value" is the private key's secret number as unsigned
big-endian bytes, padded to the key's size: the private exponent d (modulus
length) for an RSA key, the scalar (field length) for an EC key.

Private keys are written to 0600 files and never printed; what is printed is
paths and public fingerprints. A certificate is public: commit it.

    --origin URL        a repository's bundle-signing certificate (repeatable)
    --developer         the primary developer's certificate (urn:enginehost:developer)
    --revocations ID    a revocation-list signer for catalog ID ("enginehost",
                        or an added repository's owner/repo)
"""

import argparse
import datetime
import hashlib
import importlib.util
import os
import sys
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.x509.oid import NameOID

SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))
import bundle_jws  # noqa: E402

_spec = importlib.util.spec_from_file_location("derive_official_key", SCRIPTS / "derive-official-key.py")
derive_official_key = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(derive_official_key)

SEED_SALT = b"droidtop-ca-root-v1"
SEED_INFO = b"enginehost-plugin-root-master-seed"
VALIDITY_DAYS = 3 * 365


def core_raw_private_value(key) -> bytes:
    """The Core key's secret number, unsigned big-endian, padded to the key's size."""
    if isinstance(key, rsa.RSAPrivateKey):
        return key.private_numbers().d.to_bytes((key.key_size + 7) // 8, "big")
    if isinstance(key, ec.EllipticCurvePrivateKey):
        return key.private_numbers().private_value.to_bytes((key.curve.key_size + 7) // 8, "big")
    raise SystemExit("The Core key must be RSA or EC")


def master_seed(core_key) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=SEED_SALT, info=SEED_INFO).derive(core_raw_private_value(core_key))


def issue(core_key, core_cert: x509.Certificate, key, common_name: str, purpose: str, uris: list[str]) -> x509.Certificate:
    now = datetime.datetime.now(datetime.timezone.utc)
    builder = (
        x509.CertificateBuilder()
        .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, common_name)]))
        .issuer_name(core_cert.subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=VALIDITY_DAYS))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(
            x509.KeyUsage(
                digital_signature=True, content_commitment=False, key_encipherment=False, data_encipherment=False,
                key_agreement=False, key_cert_sign=False, crl_sign=False, encipher_only=False, decipher_only=False,
            ),
            critical=True,
        )
        .add_extension(x509.ExtendedKeyUsage([x509.ObjectIdentifier(bundle_jws.PURPOSES[purpose][0])]), critical=False)
        .add_extension(x509.SubjectAlternativeName([x509.UniformResourceIdentifier(uri) for uri in uris]), critical=False)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
        .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(core_cert.public_key()), critical=False)
    )
    return builder.sign(core_key, hashes.SHA256())


def write_private(path: Path, key) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))


def fingerprint(key) -> str:
    spki = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return hashlib.sha256(spki).hexdigest().upper()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--core-key", required=True, type=Path, help="The Droidtop Core private key (PEM)")
    parser.add_argument("--core-certificate", required=True, type=Path, help="The Droidtop Core certificate (PEM)")
    parser.add_argument("--out", required=True, type=Path, help="Directory for the keys (0600) and certificates")
    parser.add_argument("--origin", action="append", default=[])
    parser.add_argument("--generation", type=int, default=0, help="Repository keys: N for an origin's Nth replacement")
    parser.add_argument("--plugin", action="append", default=[],
                        help="Narrow --origin certificates to these bundle IDs (or prefix.*)")
    parser.add_argument("--developer", action="store_true")
    parser.add_argument("--revocations", action="append", default=[])
    args = parser.parse_args()

    core_key = serialization.load_pem_private_key(args.core_key.read_bytes(), password=None)
    core_cert = x509.load_pem_x509_certificate(args.core_certificate.read_bytes())
    core_spki = core_cert.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    if core_key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo) != core_spki:
        raise SystemExit("The Core key does not match the Core certificate")
    seed = master_seed(core_key)

    def emit(name: str, key, certificate: x509.Certificate) -> None:
        write_private(args.out / name / "signing-key.pem", key)
        (args.out / name / "enginehost-signing-certificate.pem").write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
        print(f"{name}: key {fingerprint(key)} -> {args.out / name}")

    for origin in args.origin:
        key = derive_official_key.derive_key(seed, derive_official_key.repository_info(origin, args.generation))
        identity = bundle_jws.origin_id(origin)
        uris = [bundle_jws.ORIGIN_URN + identity] + ["urn:droidtop:plugin:" + plugin for plugin in args.plugin]
        emit(identity.replace("/", "--"), key, issue(core_key, core_cert, key, identity, "plugin", uris))
    if args.developer:
        key = derive_official_key.derive_key(seed, b"enginehost/primary-developer-debug-signing/v1")
        emit("developer", key, issue(core_key, core_cert, key, "Enginehost developer", "plugin", [bundle_jws.DEVELOPER_URN]))
    for catalog in args.revocations:
        key = derive_official_key.derive_key(seed, b"enginehost/revocation-list-signing/v1\0" + catalog.lower().encode())
        uris = [bundle_jws.CATALOG_URN + catalog.lower()]
        emit("revocations--" + catalog.lower().replace("/", "--"), key, issue(core_key, core_cert, key, catalog, "revocations", uris))


if __name__ == "__main__":
    main()
