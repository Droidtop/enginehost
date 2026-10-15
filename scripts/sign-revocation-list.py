#!/usr/bin/env python3
"""Sign a revocation list (revocations.jws, Droidtop/tracker#550).

Run by whoever holds a revocation-list signer (provision-signing-certificates.py
--revocations CATALOG). The list replaces the one devices hold only when its
--sequence is higher, so pass the last published sequence plus one and repeat
every entry still revoked: each list is complete, not a delta.

    --catalog enginehost      Enginehost's own list, published beside the
                              plugins index (droidtop-platforms plugins/revocations.jws)
    --catalog owner/repo      an added repository's list, revocations.jws on its plugin branch

Revoke a certificate by the SHA-256 of its DER (--certificate PEM or
--cert-sha256 HEX), or a key by the SHA-256 of its SubjectPublicKeyInfo
(--key-sha256 HEX; this is how a legacy raw signing key is stopped).
"""

import argparse
import hashlib
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bundle_jws  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--key", required=True, type=Path, help="The revocation-list signer's private key")
    parser.add_argument("--chain", required=True, type=Path, help="Its certificate (PEM), then any intermediates")
    parser.add_argument("--catalog", required=True)
    parser.add_argument("--sequence", required=True, type=int)
    parser.add_argument("--certificate", action="append", default=[], type=Path)
    parser.add_argument("--cert-sha256", action="append", default=[])
    parser.add_argument("--key-sha256", action="append", default=[])
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    if args.sequence < 0:
        raise SystemExit("--sequence is never negative")
    chain = bundle_jws.pem_certificates(args.chain.read_text(encoding="utf-8"))
    if bundle_jws.CATALOG_URN + args.catalog.lower() not in (uri.lower() for uri in bundle_jws.uris(chain[0])):
        raise SystemExit(f"the signer's certificate does not speak for catalog {args.catalog}")
    certificates = [hashlib.sha256(der).hexdigest() for path in args.certificate
                    for der in bundle_jws.pem_certificates(path.read_text(encoding="utf-8"))]
    digests = certificates + [value.lower() for value in args.cert_sha256]
    keys = [value.lower() for value in args.key_sha256]
    for value in digests + keys:
        if len(value) != 64 or any(c not in "0123456789abcdef" for c in value):
            raise SystemExit("not a SHA-256: " + value)
    payload = {
        "type": "droidtop-revocations",
        "version": 1,
        "catalog": args.catalog.lower(),
        "sequence": args.sequence,
        "issuedAt": int(time.time()),
        "certSha256": sorted(set(digests)),
        "keySha256": sorted(set(keys)),
    }
    content = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    args.out.write_text(bundle_jws.sign(content, args.key, chain, "revocations", detached=False) + "\n", encoding="ascii")
    print(f"{args.out}: {args.catalog} sequence {args.sequence}, {len(payload['certSha256'])} certificates, "
          f"{len(payload['keySha256'])} keys")


if __name__ == "__main__":
    main()
