#!/usr/bin/env python3
"""Test helper: writes minimal zips with an APK Signature Scheme v2 block carrying a given certificate (DER).
The signature bytes are dummies: the reader under test only reads certificates, it does not verify.
Usage: make_fake_apk.py cert.der one-signer.apk two-signers.apk unsigned.apk"""
import struct, sys, zipfile, io

def lp(b):  # length-prefixed
    return struct.pack("<I", len(b)) + b

def v2_value(certs):
    signers = b""
    for c in certs:
        signed_data = lp(b"") + lp(lp(c)) + lp(b"")   # digests, certificates, attributes
        signer = lp(signed_data) + lp(b"") + lp(b"")  # signed data, signatures, public key
        signers += lp(signer)
    return lp(signers)

def build(certs, path):
    z = io.BytesIO()
    with zipfile.ZipFile(z, "w") as f:
        f.writestr("classes.dex", b"dex")
    data = z.getvalue()
    eocd = data.rfind(b"PK\x05\x06")
    cd_off = struct.unpack_from("<I", data, eocd + 16)[0]
    pair = struct.pack("<Q", 4 + len(v2_value(certs))) + struct.pack("<I", 0x7109871A) + v2_value(certs)
    size = len(pair) + 24
    block = struct.pack("<Q", size) + pair + struct.pack("<Q", size) + b"APK Sig Block 42"
    new = data[:cd_off] + block + data[cd_off:eocd] + data[eocd:eocd + 16] + struct.pack("<I", cd_off + len(block)) + data[eocd + 20:]
    open(path, "wb").write(new)

cert = open(sys.argv[1], "rb").read()
build([cert], sys.argv[2])
build([cert, cert], sys.argv[3])
z = io.BytesIO()
with zipfile.ZipFile(z, "w") as f:
    f.writestr("classes.dex", b"dex")
open(sys.argv[4], "wb").write(z.getvalue())
