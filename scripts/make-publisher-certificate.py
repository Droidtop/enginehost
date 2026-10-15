#!/usr/bin/env python3
"""A third-party publisher's format-v1 signing certificate (Droidtop/tracker#550).

A repository a person added to Enginehost is anchored at the key they pinned
for it (its enginehost-public-key.json). To sign in format v1 with that same
key, the publisher makes a self-signed certificate for it: CA:FALSE, keyUsage
digitalSignature, the plugin-bundle extended key usage and the origin URN,
which build-engine-bundle.py --certificate-key/--certificate-chain then puts in
every bundle's JWS. Nothing about what a person pinned changes, so nobody is
asked again.

    make-publisher-certificate.py --key signing-key.pem \
        --origin https://github.com/someone/their-plugin \
        --out enginehost-signing-certificate.pem

The private key is only read; nothing secret is written or printed.
"""

import argparse
import datetime
import sys
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.x509.oid import NameOID

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bundle_jws  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--key", required=True, type=Path, help="The repository's signing key (the one people pinned)")
    parser.add_argument("--origin", required=True, help="The repository, https://github.com/<owner>/<repo>")
    parser.add_argument("--plugin", action="append", default=[], help="Narrow it to these bundle IDs (or prefix.*)")
    parser.add_argument("--days", type=int, default=3 * 365)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    key = serialization.load_pem_private_key(args.key.read_bytes(), password=None)
    identity = bundle_jws.origin_id(args.origin)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, identity)])
    now = datetime.datetime.now(datetime.timezone.utc)
    uris = [bundle_jws.ORIGIN_URN + identity] + ["urn:droidtop:plugin:" + plugin for plugin in args.plugin]
    certificate = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=args.days))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(
            x509.KeyUsage(
                digital_signature=True, content_commitment=False, key_encipherment=False, data_encipherment=False,
                key_agreement=False, key_cert_sign=False, crl_sign=False, encipher_only=False, decipher_only=False,
            ),
            critical=True,
        )
        .add_extension(x509.ExtendedKeyUsage([x509.ObjectIdentifier(bundle_jws.PURPOSES["plugin"][0])]), critical=False)
        .add_extension(x509.SubjectAlternativeName([x509.UniformResourceIdentifier(uri) for uri in uris]), critical=False)
        .sign(key, hashes.SHA256())
    )
    args.out.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    print(f"{args.out}: self-signed for {identity}")


if __name__ == "__main__":
    main()
