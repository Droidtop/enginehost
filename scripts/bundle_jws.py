"""Signature format v1: JWS with an x5c chain to Droidtop Core (Droidtop/tracker#550).

Shared by build-engine-bundle.py (signs), verify-engine-bundle.py (checks a
bundle offline before it is published) and sign-revocation-list.py. Like the
rest of the signing tooling it needs only the standard library and the
openssl command line, so nothing but first-party code runs beside a key.

The app's verifier (CertificateSignatures.kt) is the security boundary; this
one mirrors it so CI refuses a bundle the app would refuse.
"""

import base64
import datetime
import json
import re
import subprocess
import tempfile
from pathlib import Path

PURPOSE_ARC = "2.25.230248497294207031301727532441854392946"
PURPOSES = {
    "plugin": (PURPOSE_ARC + ".1", "droidtop-plugin+jws"),
    "catalog": (PURPOSE_ARC + ".2", "droidtop-catalog+jws"),
    "revocations": (PURPOSE_ARC + ".3", "droidtop-revocations+jws"),
}
ORIGIN_URN = "urn:droidtop:origin:"
CATALOG_URN = "urn:droidtop:catalog:"
DEVELOPER_URN = "urn:enginehost:developer"


class Refused(Exception):
    pass


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64url_decode(text: str) -> bytes:
    if not re.fullmatch(r"[A-Za-z0-9_-]*", text):
        raise Refused("not base64url")
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def openssl(*arguments: str, data: bytes | None = None) -> bytes:
    result = subprocess.run(["openssl", *arguments], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise Refused("openssl " + arguments[0] + ": " + result.stderr.decode(errors="replace").strip())
    return result.stdout


def pem_certificates(text: str) -> list[bytes]:
    """Every certificate in a PEM file, as DER, in file order."""
    blocks = re.findall(r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", text, re.S)
    if not blocks:
        raise Refused("no PEM certificate found")
    return [base64.b64decode("".join(block.split())) for block in blocks]


def certificate_spki(der: bytes) -> bytes:
    public_pem = openssl("x509", "-inform", "DER", "-noout", "-pubkey", data=der)
    return openssl("pkey", "-pubin", "-outform", "DER", data=public_pem)


def private_key_spki(key: Path) -> bytes:
    return openssl("pkey", "-in", str(key), "-pubout", "-outform", "DER")


def key_algorithm(spki: bytes) -> str:
    """ES256 for a P-256 key, RS256 for RSA of 2048 bits or more; anything else is refused."""
    text = openssl("pkey", "-pubin", "-inform", "DER", "-noout", "-text", data=spki).decode(errors="replace")
    if "prime256v1" in text or "P-256" in text:
        return "ES256"
    bits = re.search(r"Public-Key: \((\d+) bit", text)
    if "RSA" in text.upper() and bits and int(bits.group(1)) >= 2048:
        return "RS256"
    raise Refused("signing keys must be EC P-256 or RSA of 2048 bits or more")


def pem_public_key(text: str) -> bytes:
    """A PEM `PUBLIC KEY` block as SubjectPublicKeyInfo DER: the pinned Droidtop Core key."""
    match = re.search(r"-----BEGIN PUBLIC KEY-----(.*?)-----END PUBLIC KEY-----", text, re.S)
    if not match:
        raise Refused("no PEM public key found")
    spki = base64.b64decode("".join(match.group(1).split()))
    openssl("pkey", "-pubin", "-inform", "DER", "-noout", data=spki)
    return spki


def _der_element(data: bytes, offset: int) -> tuple[int, int, int]:
    """(tag, start of content, end of element) of the DER element at [offset]."""
    tag = data[offset]
    length = data[offset + 1]
    start = offset + 2
    if length & 0x80:
        count = length & 0x7F
        length = int.from_bytes(data[start:start + count], "big")
        start += count
    return tag, start, start + length


_DIGESTS = {
    "1.2.840.113549.1.1.11": "-sha256", "1.2.840.113549.1.1.12": "-sha384", "1.2.840.113549.1.1.13": "-sha512",
    "1.2.840.10045.4.3.2": "-sha256", "1.2.840.10045.4.3.3": "-sha384",
}


def _decode_oid(content: bytes) -> str:
    arcs, value = [], 0
    for byte in content:
        value = (value << 7) | (byte & 0x7F)
        if not byte & 0x80:
            arcs.append(value)
            value = 0
    first = min(arcs[0] // 40, 2)
    return ".".join(str(arc) for arc in [first, arcs[0] - 40 * first, *arcs[1:]])


def verify_signed_by(certificate: bytes, issuer_spki: bytes) -> None:
    """[certificate] (DER) was signed by the key [issuer_spki], with one of the allowed algorithms."""
    tag, start, end = _der_element(certificate, 0)
    if tag != 0x30:
        raise Refused("not a certificate")
    _, tbs_start, tbs_end = _der_element(certificate, start)
    tbs = certificate[start:tbs_end]
    _, algorithm_start, algorithm_end = _der_element(certificate, tbs_end)
    oid_tag, oid_start, oid_end = _der_element(certificate, algorithm_start)
    algorithm = _decode_oid(certificate[oid_start:oid_end]) if oid_tag == 0x06 else ""
    if algorithm not in _DIGESTS:
        raise Refused("unsupported certificate signature algorithm " + algorithm)
    bits_tag, bits_start, bits_end = _der_element(certificate, algorithm_end)
    if bits_tag != 0x03 or certificate[bits_start] != 0:
        raise Refused("malformed certificate signature")
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        (root / "tbs").write_bytes(tbs)
        (root / "signature").write_bytes(certificate[bits_start + 1:bits_end])
        (root / "key.der").write_bytes(issuer_spki)
        try:
            openssl("dgst", _DIGESTS[algorithm], "-verify", str(root / "key.der"), "-keyform", "DER",
                    "-signature", str(root / "signature"), str(root / "tbs"))
        except Refused:
            raise Refused("a certificate in the chain is not signed by the next one (or by Droidtop Core)")


def check_validity(der: bytes) -> None:
    """The certificate is within its validity period now."""
    if subprocess.run(["openssl", "x509", "-inform", "DER", "-noout", "-checkend", "0"], input=der,
                      stdout=subprocess.PIPE, stderr=subprocess.PIPE).returncode:
        raise Refused("a certificate in the chain has expired")
    start = openssl("x509", "-inform", "DER", "-noout", "-startdate", data=der).decode().strip().split("=", 1)[1]
    not_before = datetime.datetime.strptime(start, "%b %d %H:%M:%S %Y %Z").replace(tzinfo=datetime.timezone.utc)
    if not_before > datetime.datetime.now(datetime.timezone.utc):
        raise Refused("a certificate in the chain is not valid yet")


def certificate_text(der: bytes, extension: str) -> str:
    """One extension of a certificate as openssl prints it, or '' when absent."""
    try:
        return openssl("x509", "-inform", "DER", "-noout", "-ext", extension, data=der).decode(errors="replace")
    except Refused:
        return ""


def uris(der: bytes) -> list[str]:
    return re.findall(r"URI:([^,\s]+)", certificate_text(der, "subjectAltName"))


def origin_id(origin: str) -> str:
    """https://github.com/Owner/Repo -> owner/repo, the id an origin URN carries."""
    match = re.fullmatch(r"https://github\.com/([^/]+)/([^/]+?)(?:\.git)?/?", origin.strip(), re.I)
    if not match:
        raise Refused("not a GitHub repository origin: " + origin)
    return (match.group(1) + "/" + match.group(2)).lower()


def _ecdsa_der_to_raw(der: bytes) -> bytes:
    if der[0] != 0x30:
        raise Refused("not an ECDSA signature")
    offset = 2 if der[1] < 0x80 else 2 + (der[1] & 0x7F)
    parts = []
    for _ in range(2):
        if der[offset] != 0x02:
            raise Refused("not an ECDSA signature")
        length = der[offset + 1]
        value = int.from_bytes(der[offset + 2:offset + 2 + length], "big")
        parts.append(value.to_bytes(32, "big"))
        offset += 2 + length
    return parts[0] + parts[1]


def _ecdsa_raw_to_der(raw: bytes) -> bytes:
    if len(raw) != 64:
        raise Refused("an ES256 signature is 64 bytes")

    def integer(value: bytes) -> bytes:
        number = int.from_bytes(value, "big")
        body = number.to_bytes(max(1, (number.bit_length() + 8) // 8), "big")
        return b"\x02" + bytes([len(body)]) + body

    body = integer(raw[:32]) + integer(raw[32:])
    return b"\x30" + bytes([len(body)]) + body


def sign(content: bytes, private_key: Path, chain: list[bytes], purpose: str, detached: bool) -> str:
    """A compact JWS over [content] by [private_key], whose certificate is chain[0]."""
    _oid, typ = PURPOSES[purpose]
    spki = private_key_spki(private_key)
    if certificate_spki(chain[0]) != spki:
        raise Refused("the signing certificate is not for this private key")
    algorithm = key_algorithm(spki)
    header = {"alg": algorithm, "typ": typ, "x5c": [base64.b64encode(der).decode("ascii") for der in chain]}
    header_part = b64url(json.dumps(header, separators=(",", ":"), sort_keys=True).encode())
    payload_part = b64url(content)
    signing_input = (header_part + "." + payload_part).encode("ascii")
    with tempfile.TemporaryDirectory() as temporary:
        message = Path(temporary) / "input"
        message.write_bytes(signing_input)
        signature = openssl("dgst", "-sha256", "-sign", str(private_key), str(message))
    if algorithm == "ES256":
        signature = _ecdsa_der_to_raw(signature)
    return header_part + "." + ("" if detached else payload_part) + "." + b64url(signature)


def verify(compact: str, purpose: str, core: bytes, content: bytes | None) -> dict:
    """
    Checks [compact] for [purpose] up to Droidtop Core's public key [core]
    (SubjectPublicKeyInfo DER) and
    no further, the way the app does: header, chain, the leaf's usage, and the
    signature. Answers what the leaf names. Raises Refused.
    """
    oid, typ = PURPOSES[purpose]
    parts = compact.strip().split(".")
    if len(parts) != 3:
        raise Refused("not a compact JWS")
    header = json.loads(b64url_decode(parts[0]))
    if "crit" in header:
        raise Refused("critical header parameters are refused")
    if header.get("typ") != typ:
        raise Refused("not a " + typ)
    algorithm = header.get("alg")
    if algorithm not in ("ES256", "RS256"):
        raise Refused("unsupported alg")
    chain = [base64.b64decode(item, validate=True) for item in header.get("x5c") or []]
    named = verify_chain(chain, purpose, core)
    leaf_spki = named["leafSpki"]
    if key_algorithm(leaf_spki) != algorithm:
        raise Refused("alg does not match the signing key")
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        (root / "leaf.der").write_bytes(leaf_spki)
        signing_payload = b64url(content) if content is not None else parts[1]
        if content is not None and parts[1]:
            raise Refused("a detached signature carries no payload")
        (root / "input").write_bytes((parts[0] + "." + signing_payload).encode("ascii"))
        signature = b64url_decode(parts[2])
        if algorithm == "ES256":
            signature = _ecdsa_raw_to_der(signature)
        (root / "signature").write_bytes(signature)
        openssl("dgst", "-sha256", "-verify", str(root / "leaf.der"), "-keyform", "DER",
                "-signature", str(root / "signature"), str(root / "input"))
    named["payload"] = b64url_decode(parts[1]) if content is None else content
    return named


def verify_chain(chain: list[bytes], purpose: str, core: bytes) -> dict:
    """
    Checks a certificate chain (DER, leaf first, Core excluded) for [purpose]
    up to Droidtop Core's public key [core] (SubjectPublicKeyInfo DER) and no
    further: each certificate signed by the next, the last by Core's key, and
    no certificate carrying Core's key. The anchor is a key, not a
    certificate (Droidtop/tracker#550), so there is no issuer name to compare.
    Answers what the leaf names. Raises Refused. An origin's published
    certificate is checked this way.
    """
    oid, typ = PURPOSES[purpose]
    if not 1 <= len(chain) <= 3:
        raise Refused("the chain is empty or too long")
    if any(certificate_spki(der) == core for der in chain):
        raise Refused("the chain may not carry a certificate holding Droidtop Core's key")
    leaf = chain[0]
    if "CA:TRUE" in certificate_text(leaf, "basicConstraints"):
        raise Refused("the signing certificate is a CA")
    usage = certificate_text(leaf, "keyUsage")
    if "Digital Signature" not in usage or "Certificate Sign" in usage:
        raise Refused("the signing certificate's key usage is not digitalSignature alone")
    if oid not in certificate_text(leaf, "extendedKeyUsage"):
        raise Refused("the signing certificate is not for " + typ)
    leaf_spki = certificate_spki(leaf)
    key_algorithm(leaf_spki)
    for below, intermediate in enumerate(chain[1:]):
        constraints = certificate_text(intermediate, "basicConstraints")
        if "CA:TRUE" not in constraints:
            raise Refused("an intermediate certificate is not a CA")
        path_length = re.search(r"pathlen:(\d+)", constraints)
        if path_length and int(path_length.group(1)) < below:
            raise Refused("an intermediate's path length constraint is exceeded")
        if "Certificate Sign" not in certificate_text(intermediate, "keyUsage"):
            raise Refused("an intermediate certificate may not sign certificates")
    for der in chain:
        check_validity(der)
    for child, parent in zip(chain, chain[1:]):
        if openssl("x509", "-inform", "DER", "-noout", "-issuer", "-nameopt", "RFC2253", data=child).split(b"=", 1)[1] \
                != openssl("x509", "-inform", "DER", "-noout", "-subject", "-nameopt", "RFC2253", data=parent).split(b"=", 1)[1]:
            raise Refused("the certificate chain is out of order")
        verify_signed_by(child, certificate_spki(parent))
    verify_signed_by(chain[-1], core)
    names = uris(leaf)
    return {
        "origins": {name[len(ORIGIN_URN):].lower() for name in names if name.lower().startswith(ORIGIN_URN)},
        "catalogs": {name[len(CATALOG_URN):].lower() for name in names if name.lower().startswith(CATALOG_URN)},
        "developer": DEVELOPER_URN in (name.lower() for name in names),
        "leafSpki": leaf_spki,
    }
