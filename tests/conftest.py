"""Live-gateway test fixtures.

Environment (defaults match docker/docker-compose.yml):
    GATEWAY_URL        http://localhost:8090
    GATEWAY_USERNAME   admin
    GATEWAY_PASSWORD   password
    MONGO_URI          mongodb://vt:vt-secret@localhost:27018/?authSource=admin   (direct verification)
    MOCK_LLM_URL       http://localhost:8011                                      (mock OpenAI endpoint)
    VT_CONNECTOR       VoiceMongo        MongoDB Connector connection the module writes through
    VT_MONGO_HOST      mongo:27017       Mongo host as seen *from the gateway container*
    VT_MODEL_PATH      voicetranscription/models/ggml-tiny.bin
"""
from __future__ import annotations

import hashlib
import os
import time
import wave
from pathlib import Path

import pymongo
import pytest
import requests

from gateway_client import GatewayClient, from_env

FIXTURES = Path(__file__).parent / "fixtures"
CONNECTOR = os.environ.get("VT_CONNECTOR", "VoiceMongo")
DATABASE = "voice"
COLLECTION = "transcriptions_test"
LLM_KEY = "test-llm-key"
LOCAL_MODEL = Path(__file__).parent.parent / "docker" / "models" / "ggml-tiny.bin"


def model_sha256() -> str:
    """SHA-256 of the model the docker stack mounts ('' if the suite runs against a remote gateway)."""
    if not LOCAL_MODEL.exists():
        return ""
    h = hashlib.sha256()
    with open(LOCAL_MODEL, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def pcm_from_wav(name: str) -> bytes:
    """Raw 16 kHz mono PCM16LE frames of a fixture (what the Perspective component uploads)."""
    with wave.open(str(FIXTURES / name)) as w:
        assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (16000, 1, 2)
        return w.readframes(w.getnframes())


def module_settings(**overrides) -> dict:
    settings = {
        "mongo": {"connector": CONNECTOR, "collection": COLLECTION},
        "whisper": {
            "modelPath": os.environ.get("VT_MODEL_PATH", "voicetranscription/models/ggml-tiny.bin"),
            "defaultLanguage": "en", "autoDetect": False, "threads": 4, "chunkIntervalMs": 3000,
            "retentionDays": 0, "maxRecordingSeconds": 300, "maxConcurrentTranscriptions": 2,
            "modelSha256": os.environ.get("VT_MODEL_SHA256", model_sha256()),
        },
        "llm": {
            "enabled": True, "provider": "custom", "endpointUrl": "http://mock-llm:8000/v1",
            "model": "mock-summarizer", "systemPrompt": "Summarize for {{username}} ({{roles}}) in {{language}}.",
            "apiKey": {"mode": "embedded", "value": LLM_KEY}, "timeoutSeconds": 30, "temperature": 0.2,
        },
    }
    for section, values in overrides.items():
        settings[section].update(values)
    return settings


@pytest.fixture(scope="session")
def client() -> GatewayClient:
    return from_env().login()


@pytest.fixture(scope="session")
def mongo_db():
    uri = os.environ.get("MONGO_URI", "mongodb://vt:vt-secret@localhost:27018/?authSource=admin")
    mc = pymongo.MongoClient(uri, serverSelectionTimeoutMS=5000)
    mc.admin.command("ping")
    yield mc[DATABASE]
    mc.close()


@pytest.fixture(scope="session")
def mock_llm_url() -> str:
    return os.environ.get("MOCK_LLM_URL", "http://localhost:8011")


def ensure_connector(client: GatewayClient) -> None:
    """Creates the MongoDB Connector connection through the gateway's own config API (if missing).

    The password is stored as an embedded secret: the gateway's /encryption/encrypt endpoint returns the
    JWE, exactly what the connector's config page would persist.
    """
    names = client.request("GET", "/data/api/v1/resources/names/ignition/service-connector", base="")
    if names.ok and CONNECTOR in str(names.json()):
        return
    jwe = client.request("POST", "/data/api/v1/encryption/encrypt", data=b"vt-secret",
                         headers={"Content-Type": "text/plain"}, base="").json()
    body = [{
        "name": CONNECTOR, "enabled": True, "description": "Voice transcription test database",
        "config": {"profile": {"type": "mongoDB"}, "settings": {
            "scheme": "mongodb", "hosts": [os.environ.get("VT_MONGO_HOST", "mongo:27017")],
            "databaseName": DATABASE, "username": "vt",
            "password": {"type": "Embedded", "data": jwe},
            "connectionProperties": "authSource=admin", "connectionRetryMs": 5000,
        }},
    }]
    r = client.request("POST", "/data/api/v1/resources/ignition/service-connector", json=body, base="")
    assert r.status_code == 200, r.text


def wait_until(predicate, timeout: float, what: str):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = predicate()
        if last:
            return last
        time.sleep(1)
    raise AssertionError(f"Timed out after {timeout}s waiting for {what} (last={last!r})")


@pytest.fixture(scope="session", autouse=True)
def provisioned(client: GatewayClient, mongo_db):
    """Connector + module settings + loaded Whisper model; restores the original module settings after."""
    original = client.get("/config").json()
    ensure_connector(client)
    mongo_db.create_collection(COLLECTION) if COLLECTION not in mongo_db.list_collection_names() else None
    mongo_db[COLLECTION].delete_many({})

    r = client.put("/config", json=module_settings())
    assert r.status_code == 200, r.text
    wait_until(lambda: client.get("/status").json()["whisper"]["ready"], 60, "Whisper model to load")
    wait_until(lambda: client.get("/status").json()["mongo"]["connected"], 60, "MongoDB connector to connect")
    yield
    original.pop("providers", None)
    for section in ("mongo", "llm"):
        original[section].pop("password", None)
    original["llm"]["apiKey"] = {"mode": "keep"}
    client.put("/config", json=original)


@pytest.fixture()
def fresh_client() -> GatewayClient:
    """An independent logged-in session (separate cookie jar)."""
    return from_env().login()


@pytest.fixture()
def anonymous() -> requests.Session:
    return requests.Session()
