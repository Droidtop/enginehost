"""Signature format v1: JWS with an x5c chain to Droidtop Core (Droidtop/tracker#550).

Shared by build-engine-bundle.py (signs), verify-engine-bundle.py (checks a
bundle offline before it is published) and sign-revocation-list.py. Like the
rest of the signing tooling it needs only the standard library and the
openssl command line, so nothing but first-party code runs beside a key.

The app's verifier (CertificateSignatures.kt) is the security boundary; this
one mirrors it so CI refuses a bundle the app would refuse.
"""

import base64
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
    Checks [compact] for [purpose] up to the Core certificate [core] (DER) and
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
    if not 1 <= len(chain) <= 3:
        raise Refused("x5c is empty or too long")
    core_spki = certificate_spki(core)
    if any(der == core or certificate_spki(der) == core_spki for der in chain):
        raise Refused("x5c may not carry the Core certificate")
    leaf = chain[0]
    if "CA:TRUE" in certificate_text(leaf, "basicConstraints"):
        raise Refused("the signing certificate is a CA")
    usage = certificate_text(leaf, "keyUsage")
    if "Digital Signature" not in usage or "Certificate Sign" in usage:
        raise Refused("the signing certificate's key usage is not digitalSignature alone")
    if oid not in certificate_text(leaf, "extendedKeyUsage"):
        raise Refused("the signing certificate is not for " + typ)
    leaf_spki = certificate_spki(leaf)
    if key_algorithm(leaf_spki) != algorithm:
        raise Refused("alg does not match the signing key")
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)

        def pem(der: bytes) -> str:
            return openssl("x509", "-inform", "DER", data=der).decode()

        (root / "core.pem").write_text(pem(core))
        (root / "leaf.pem").write_text(pem(leaf))
        (root / "intermediates.pem").write_text("".join(pem(der) for der in chain[1:]))
        # -partial_chain makes Core the trust anchor: the walk stops there and
        # nothing above it (the UCM root) is read or needed.
        command = ["verify", "-partial_chain", "-trusted", str(root / "core.pem"), "-purpose", "any"]
        if len(chain) > 1:
            command += ["-untrusted", str(root / "intermediates.pem")]
        openssl(*command, str(root / "leaf.pem"))
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
    names = uris(leaf)
    return {
        "origins": {name[len(ORIGIN_URN):].lower() for name in names if name.lower().startswith(ORIGIN_URN)},
        "catalogs": {name[len(CATALOG_URN):].lower() for name in names if name.lower().startswith(CATALOG_URN)},
        "developer": DEVELOPER_URN in (name.lower() for name in names),
        "payload": b64url_decode(parts[1]) if content is None else content,
        "leafSpki": leaf_spki,
    }
