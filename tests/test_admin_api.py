"""Module REST endpoints: configuration, status, connectors, secrets, access control."""
from gateway_client import API

from conftest import COLLECTION, CONNECTOR, LLM_KEY, module_settings, wait_until


def test_status_reports_all_subsystems(client):
    s = client.get("/status").json()
    assert s["moduleId"] == "com.wargoetz.voicetranscription"
    assert s["whisper"]["ready"] is True
    assert s["whisper"]["modelPath"].endswith("ggml-tiny.bin")
    assert s["mongo"] == {**s["mongo"], "configured": True, "connected": True, "connector": CONNECTOR,
                          "collection": COLLECTION, "database": "voice"}
    assert s["llm"]["enabled"] is True


def test_config_never_returns_secret_values(client):
    cfg = client.get("/config").json()
    api_key = cfg["llm"]["apiKey"]
    assert api_key == {"configured": True, "mode": "embedded"}
    assert LLM_KEY not in client.get("/config").text
    assert {p["id"] for p in cfg["providers"]} == {"openai", "gemini", "custom"}


def test_config_round_trip_and_keep_secret(client):
    r = client.put("/config", json={"whisper": {"chunkIntervalMs": 2500}, "llm": {"apiKey": {"mode": "keep"}}})
    assert r.status_code == 200, r.text
    cfg = client.get("/config").json()
    assert cfg["whisper"]["chunkIntervalMs"] == 2500
    assert cfg["llm"]["apiKey"]["configured"] is True  # "keep" did not clear it
    client.put("/config", json=module_settings())


def test_invalid_config_is_rejected(client):
    r = client.put("/config", json={"mongo": {"collection": "system.users"}})
    assert r.status_code == 422, r.text
    assert "Settings rejected" in r.json()["error"]
    assert client.get("/config").json()["mongo"]["collection"] == COLLECTION


def test_referenced_secret_requires_existing_provider(client):
    r = client.put("/config", json={"llm": {"apiKey": {"mode": "referenced", "providerName": "nope",
                                                       "secretName": "x"}}})
    assert r.status_code == 400
    assert "does not exist" in r.json()["error"]


def test_lists_mongodb_connectors(client):
    connectors = {c["name"]: c for c in client.get("/mongo/connectors").json()}
    assert connectors[CONNECTOR]["database"] == "voice"


def test_lists_collections_through_connector(client):
    r = client.get("/mongo/collections", params={"connector": CONNECTOR})
    assert r.status_code == 200, r.text
    assert COLLECTION in r.json()["collections"]
    assert not any(c.startswith("system.") for c in r.json()["collections"])


def test_unknown_connector_is_a_clean_error(client):
    r = client.get("/mongo/collections", params={"connector": "does-not-exist"})
    assert r.status_code == 502
    assert "MongoDB error" in r.json()["error"]


def test_llm_models_use_decrypted_api_key(client):
    # The mock rejects requests without "Bearer test-llm-key", so a 200 proves Secrets-API decryption.
    r = client.get("/llm/models")
    assert r.status_code == 200, r.text
    assert r.json()["models"] == ["mock-large", "mock-summarizer"]
    assert r.json()["hidden"] == 1  # text-embedding-mock can't summarize


SAVED_LLM = "http://mock-llm:8000/v1"
OTHER_LLM = "http://vt-mock-llm:8000/v1"  # same mock, different URL (container name)


def test_llm_models_for_unsaved_form_values(client):
    """The settings page lists models for the provider it is showing, before anything is saved."""
    r = client.post("/llm/models", json={"provider": "custom", "endpointUrl": SAVED_LLM + "/",
                                         "apiKey": {"mode": "keep"}})
    assert r.status_code == 200, r.text
    assert r.json() == {"endpointUrl": SAVED_LLM, "models": ["mock-large", "mock-summarizer"], "hidden": 1}

    r = client.post("/llm/models", json={"provider": "custom", "endpointUrl": OTHER_LLM,
                                         "apiKey": {"mode": "embedded", "value": LLM_KEY}})
    assert r.status_code == 200, r.text
    assert r.json()["endpointUrl"] == OTHER_LLM
    cfg = client.get("/config").json()["llm"]  # nothing was saved
    assert cfg["endpointUrl"] == SAVED_LLM and cfg["apiKey"]["mode"] == "embedded"


def test_saved_llm_key_only_goes_to_saved_endpoint(client):
    # The mock 401s without the key, so this proves the stored key was withheld from the other URL.
    r = client.post("/llm/models", json={"provider": "custom", "endpointUrl": OTHER_LLM, "apiKey": {"mode": "keep"}})
    assert r.status_code == 502, r.text
    assert "only sent to the saved endpoint" in r.json()["error"]


def test_llm_models_preview_validates_endpoint_and_needs_write(client):
    r = client.post("/llm/models", json={"provider": "custom", "endpointUrl": "file:///etc/passwd"})
    assert r.status_code == 400
    r = client.http.post(client.base + API + "/llm/models", json={"provider": "custom"})  # no CSRF token
    assert r.status_code in (401, 403)


def test_secret_providers_listed(client):
    r = client.get("/secrets/providers")
    assert r.status_code == 200
    assert isinstance(r.json(), list)


def test_anonymous_requests_are_refused(client, anonymous):
    for path in ("/status", "/config", "/mongo/connectors", "/transcripts"):
        r = anonymous.get(client.base + API + path)
        assert r.status_code in (401, 403), path
    r = anonymous.post(client.base + API + "/recordings", json={})
    assert r.status_code in (401, 403)


def test_writes_require_csrf_token(client):
    r = client.http.put(client.base + API + "/config", json={"whisper": {"threads": 2}})
    assert r.status_code in (401, 403)


def test_forged_perspective_session_is_refused(client):
    r = client.post("/recordings", json={}, headers={"X-Perspective-Session-Id": "not-a-session",
                                                     "X-Perspective-Project": "VoiceDemo"})
    assert r.status_code == 403


def test_model_checksum_mismatch_blocks_load(client):
    """whisper.cpp can crash the JVM on a malformed model, so a pinned SHA-256 must be enforced."""
    r = client.put("/config", json={"whisper": {"modelSha256": "0" * 64}, "llm": {"apiKey": {"mode": "keep"}}})
    assert r.status_code == 200, r.text
    try:
        status = client.get("/status").json()["whisper"]
        assert status["ready"] is False
        assert "checksum mismatch" in status["status"]
        assert client.post("/recordings", json={}).status_code == 503
        assert client.put("/config", json={"whisper": {"modelSha256": "not-hex"}}).status_code == 422
    finally:
        client.put("/config", json=module_settings())
        wait_until(lambda: client.get("/status").json()["whisper"]["ready"], 60, "Whisper model to reload")


def test_models_lists_bin_files_and_catalog(client):
    r = client.get("/models")
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["exists"] is True
    assert any(f["name"] == "ggml-tiny.bin" for f in body["files"])
    tiny = next(c for c in body["catalog"] if c["id"] == "tiny")
    assert tiny["installed"] is True
    assert {"base", "small", "medium", "large-v3"} <= {c["id"] for c in body["catalog"]}


def test_models_dir_param_browses_other_folder(client):
    r = client.get("/models", params={"dir": "voicetranscription/does-not-exist"})
    assert r.status_code == 200
    assert r.json()["exists"] is False and r.json()["files"] == []


def test_model_download_rejects_unknown_and_installed(client):
    assert client.post("/models/download", json={"id": "../../evil"}).status_code == 404
    assert client.post("/models/download", json={"id": "tiny"}).status_code == 409
