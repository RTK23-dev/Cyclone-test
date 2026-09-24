from __future__ import annotations

import json
import socket
import struct
import threading

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.serialization import load_der_public_key

from cyclone_device_gateway.desktop_runtime import lan_share
from cyclone_device_gateway.desktop_runtime.lan_share import (
    LanShareClient,
    LanShareError,
    Opener,
    PhoneTrust,
    b64,
    derive_keys,
    hkdf,
    transcript,
    unb64,
)


def _pub(key) -> str:
    return b64(key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))


def _sign(key, text: str) -> str:
    return b64(key.sign(text.encode("utf-8"), ec.ECDSA(hashes.SHA256())))


class FakePhone:
    """Python mirror of LanShareServer.kt: hello, verify PC, sign, then sealed records."""

    def __init__(self, phone_key, pc_public_key: str, *, trusted: bool = True, records: list[bytes] | None = None):
        self.phone_key = phone_key
        self.pc_public_key = pc_public_key
        self.trusted = trusted
        self.records = records if records is not None else [bytes([lan_share.TYPE_JPEG]) + b"\xff\xd8jpeg\xff\xd9"]
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.port = self.listener.getsockname()[1]
        self.thread = threading.Thread(target=self._serve, daemon=True)
        self.thread.start()

    def _serve(self) -> None:
        conn, _ = self.listener.accept()
        with conn:
            reader = conn.makefile("rb")
            eph = ec.generate_private_key(ec.SECP256R1())
            phone_nonce = b64(b"n" * 16)
            hello = {"v": lan_share.VERSION, "phoneId": "phone-1", "phoneNonce": phone_nonce, "phoneEph": _pub(eph)}
            conn.sendall((json.dumps(hello) + "\n").encode())
            pc = json.loads(reader.readline())
            text = transcript("phone-1", pc["trustId"], phone_nonce, pc["pcNonce"], hello["phoneEph"], pc["pcEph"])
            try:
                if not self.trusted:
                    raise ValueError("NOT_TRUSTED")
                load_der_public_key(unb64(self.pc_public_key)).verify(unb64(pc["pcSig"]), text.encode(), ec.ECDSA(hashes.SHA256()))
            except Exception:
                conn.sendall(b'{"ok":false,"code":"NOT_TRUSTED"}\n')
                return
            conn.sendall((json.dumps({"ok": True, "phoneSig": _sign(self.phone_key, text)}) + "\n").encode())
            shared = eph.exchange(ec.ECDH(), load_der_public_key(unb64(pc["pcEph"])))
            key, _ = derive_keys(shared, text)
            aead = AESGCM(key)
            for counter, payload in enumerate(self.records):
                iv = b"\x00\x00\x00\x00" + struct.pack(">Q", counter)
                sealed = iv + aead.encrypt(iv, payload, lan_share.VERSION.encode())
                conn.sendall(struct.pack(">I", len(sealed)) + sealed)


def _client(port: int, phone_key, pc_key, phone_id: str = "phone-1") -> LanShareClient:
    trust = PhoneTrust(trust_id="trust-1", phone_id=phone_id, phone_public_key=_pub(phone_key))
    return LanShareClient("127.0.0.1", port, trust, lambda text: _sign(pc_key, text))


def test_hkdf_matches_the_phone_vector():
    # Same vector as LanShareServerTest.hkdfMatchesTheSharedVector on the phone.
    assert b64(hkdf(b"cyclone", b"salt", f"{lan_share.VERSION} phone->pc")) == "m8as1vvqDP8l5-qa4kxJXIfbBQWBtkEUzw4OXxMYlbE"


def test_trusted_pc_reads_encrypted_frames_from_the_phone():
    phone_key, pc_key = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
    phone = FakePhone(phone_key, _pub(pc_key))
    with _client(phone.port, phone_key, pc_key) as client:
        kind, payload = next(client.records())
    assert kind == lan_share.TYPE_JPEG and payload == b"\xff\xd8jpeg\xff\xd9"


def test_refusal_and_impostors_are_named():
    phone_key, pc_key = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
    refused = FakePhone(phone_key, _pub(pc_key), trusted=False)
    with pytest.raises(LanShareError) as error:
        _client(refused.port, phone_key, pc_key).open()
    assert error.value.code == "NOT_TRUSTED"
    # A different phone key at the address: the PC never reads its frames.
    impostor = FakePhone(ec.generate_private_key(ec.SECP256R1()), _pub(pc_key))
    with pytest.raises(LanShareError) as error:
        _client(impostor.port, phone_key, pc_key).open()
    assert error.value.code == "PHONE_NOT_VERIFIED"
    other = FakePhone(phone_key, _pub(pc_key))
    with pytest.raises(LanShareError) as error:
        _client(other.port, phone_key, pc_key, phone_id="phone-2").open()
    assert error.value.code == "WRONG_PHONE"


def test_records_cannot_be_replayed_or_tampered():
    key = bytes(range(32))
    aead = AESGCM(key)
    iv0 = b"\x00" * 4 + struct.pack(">Q", 0)
    first = iv0 + aead.encrypt(iv0, b"a", lan_share.VERSION.encode())
    opener = Opener(key)
    assert opener.open(first) == b"a"
    with pytest.raises(LanShareError) as error:
        opener.open(first)
    assert error.value.code == "RECORD_OUT_OF_ORDER"
    tampered = first[:-1] + bytes([first[-1] ^ 1])
    with pytest.raises(LanShareError) as error:
        Opener(key).open(tampered)
    assert error.value.code == "RECORD_TAMPERED"
