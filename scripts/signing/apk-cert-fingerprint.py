#!/usr/bin/env python3
"""Read-only: prints the signing certificate SHA-256 of an APK, using only the Python standard library.

Reads the APK Signature Scheme v3 / v2 block (and the v1 META-INF/*.RSA|DSA|EC file as a fallback).
It does NOT verify the signature (use apksigner for that); it only reads the public certificate that the APK
carries, so it is safe to run on any APK and never touches a key or a secret.

Usage: apk-cert-fingerprint.py app.apk        -> one line per signer: "<sha256 hex lowercase>"
Exit codes: 0 ok, 1 no signature / unreadable, 2 usage.
"""
import hashlib
import struct
import sys
import zipfile

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
ID_V2, ID_V3, ID_V31 = 0x7109871A, 0xF05368C0, 0x1B93AD61


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def u64(b, o):
    return struct.unpack_from("<Q", b, o)[0]


def length_prefixed(data, off):
    n = u32(data, off)
    if n > len(data) - off - 4:
        raise ValueError("bad length")
    return data[off + 4: off + 4 + n], off + 4 + n


def find_signing_block(path):
    with open(path, "rb") as f:
        f.seek(0, 2)
        size = f.tell()
        tail = min(size, 65557)
        f.seek(size - tail)
        buf = f.read(tail)
        eocd = buf.rfind(b"PK\x05\x06")
        if eocd < 0:
            raise ValueError("not a zip/APK (no end-of-central-directory)")
        cd_off = u32(buf, eocd + 16)
        if cd_off < 24:
            return None
        f.seek(cd_off - 24)
        footer = f.read(24)
        if footer[8:] != APK_SIG_BLOCK_MAGIC:
            return None
        block_size = u64(footer, 0)
        start = cd_off - block_size - 8
        if start < 0:
            raise ValueError("bad signing block size")
        f.seek(start)
        head = f.read(8)
        if u64(head, 0) != block_size:
            raise ValueError("signing block sizes disagree")
        pairs = f.read(block_size - 24)
    out = {}
    o = 0
    while o < len(pairs):
        n = u64(pairs, o)
        pid = u32(pairs, o + 8)
        out[pid] = pairs[o + 12: o + 8 + n]
        o += 8 + n
    return out


def certs_from_v2(value):
    """v2/v3: signers -> signer -> signed data -> digests, certificates."""
    certs = []
    signers, _ = length_prefixed(value, 0)
    o = 0
    while o < len(signers):
        signer, o = length_prefixed(signers, o)
        signed_data, _ = length_prefixed(signer, 0)
        _, p = length_prefixed(signed_data, 0)          # digests
        cert_seq, _ = length_prefixed(signed_data, p)   # certificates
        q = 0
        first = True
        while q < len(cert_seq):
            cert, q = length_prefixed(cert_seq, q)
            if first:  # the signer's own certificate; later ones are the chain
                certs.append(cert)
                first = False
    return certs


def certs_from_v1(path):
    certs = []
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            up = name.upper()
            if up.startswith("META-INF/") and up.rsplit(".", 1)[-1] in ("RSA", "DSA", "EC"):
                certs.extend(_certs_from_pkcs7(z.read(name)))
    return certs


def _certs_from_pkcs7(der):
    """Tiny DER walk: returns the first certificate in a PKCS#7 SignedData (enough for APK v1)."""
    def tlv(b, o):
        tag = b[o]
        n = b[o + 1]
        o += 2
        if n & 0x80:
            k = n & 0x7F
            n = int.from_bytes(b[o:o + k], "big")
            o += k
        return tag, o, n

    def find_cert(b, start, end):
        o = start
        while o < end:
            tag, vo, n = tlv(b, o)
            if tag == 0xA0 and b[vo] == 0x30:  # [0] certificates -> first SEQUENCE
                t2, v2, n2 = tlv(b, vo)
                return [b[vo: v2 + n2]]
            if tag in (0x30, 0xA0, 0x31, 0x02) and tag != 0x02:
                r = find_cert(b, vo, vo + n)
                if r:
                    return r
            o = vo + n
        return []
    try:
        return find_cert(der, 0, len(der))
    except Exception:
        return []


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    path = argv[1]
    try:
        block = find_signing_block(path)
    except (OSError, ValueError) as e:
        print("error: %s" % e, file=sys.stderr)
        return 1
    certs = []
    scheme = None
    if block:
        for pid, name in ((ID_V31, "v3.1"), (ID_V3, "v3"), (ID_V2, "v2")):
            if pid in block:
                try:
                    certs = certs_from_v2(block[pid])
                except ValueError as e:
                    print("error: %s" % e, file=sys.stderr)
                    return 1
                scheme = name
                break
    if not certs:
        try:
            certs = certs_from_v1(path)
            scheme = "v1"
        except (OSError, zipfile.BadZipFile) as e:
            print("error: %s" % e, file=sys.stderr)
            return 1
    if not certs:
        print("error: no signing certificate found (unsigned APK?)", file=sys.stderr)
        return 1
    for c in certs:
        print(hashlib.sha256(c).hexdigest())
    print("scheme: %s, signers: %d" % (scheme, len(certs)), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
