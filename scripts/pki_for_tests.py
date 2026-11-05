"""Throwaway keys and certificates for the signing tests, generated per run with openssl; never a real one."""

import subprocess
from pathlib import Path


def openssl(*arguments: str) -> None:
    subprocess.run(["openssl", *arguments], check=True, capture_output=True)


def make_pki(r: Path, prefix: str) -> None:
    """<prefix>root-key.pem, <prefix>root.pub, <prefix>leaf-key.pem and <prefix>leaf.pem: a throwaway root and a plugin leaf."""
    openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(r / f"{prefix}root-key.pem"))
    openssl("req", "-new", "-x509", "-key", str(r / f"{prefix}root-key.pem"), "-subj", f"/CN=Test root {prefix}", "-days", "2",
            "-addext", "basicConstraints=critical,CA:TRUE,pathlen:0",
            "-addext", "keyUsage=critical,keyCertSign", "-out", str(r / f"{prefix}root.pem"))
    openssl("pkey", "-in", str(r / f"{prefix}root-key.pem"), "-pubout", "-out", str(r / f"{prefix}root.pub"))
    openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(r / f"{prefix}leaf-key.pem"))
    openssl("req", "-new", "-key", str(r / f"{prefix}leaf-key.pem"), "-subj", f"/CN=leaf {prefix}", "-out", str(r / f"{prefix}leaf.csr"))
    (r / f"{prefix}leaf.ext").write_text(
        "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\n"
        "extendedKeyUsage=2.25.230248497294207031301727532441854392946.1\n"
        "subjectAltName=URI:urn:droidtop:origin:droidtop/enginehost-example-plugin\n"
    )
    openssl("x509", "-req", "-in", str(r / f"{prefix}leaf.csr"), "-CA", str(r / f"{prefix}root.pem"),
            "-CAkey", str(r / f"{prefix}root-key.pem"), "-CAcreateserial", "-days", "2",
            "-extfile", str(r / f"{prefix}leaf.ext"), "-out", str(r / f"{prefix}leaf.pem"))
