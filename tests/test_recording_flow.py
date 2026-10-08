"""Audio upload streaming, partial transcripts, finalisation, and the stored MongoDB documents."""
import re
import time

import pytest

import requests
from bson import ObjectId

import noise
from conftest import COLLECTION, FIXTURES, module_settings, pcm_from_wav

L16 = {"Content-Type": "audio/L16;rate=16000;channels=1"}
CHUNK = 16000 * 2 * 3  # 3 s of PCM16, the component's default slice


def words(text: str) -> set[str]:
    return set(re.findall(r"[a-z]+", text.lower()))


def start(client, **body) -> str:
    r = client.post("/recordings", json=body)
    assert r.status_code == 201, r.text
    return r.json()["recordingId"]


def upload(client, rid: str, pcm: bytes) -> list[dict]:
    responses = []
    for seq, i in enumerate(range(0, len(pcm), CHUNK)):
        r = client.post(f"/recordings/{rid}/chunks", data=pcm[i:i + CHUNK], headers={**L16, "X-Chunk-Seq": str(seq)})
        assert r.status_code == 200, r.text
        responses.append(r.json())
    return responses


def test_streaming_partials_grow_and_final_is_complete(client):
    rid = start(client)
    responses = upload(client, rid, pcm_from_wav("pump_report.wav"))

    partials = [r["partialTranscript"] for r in responses if r.get("transcribed")]
    assert len(partials) >= 5, "every 3 s chunk should produce a partial on an idle gateway"
    assert "shift" in words(partials[0])
    assert len(partials[-1]) > len(partials[0])
    assert responses[-1]["durationSeconds"] > 21

    stop = client.post(f"/recordings/{rid}/stop").json()
    expected = {"pump", "bearing", "maintenance", "tank", "safety"}
    assert expected <= words(stop["transcript"]), stop["transcript"]
    assert stop["language"] == "en"

    # stop is idempotent
    assert client.post(f"/recordings/{rid}/stop").json()["transcript"] == stop["transcript"]
    client.delete(f"/recordings/{rid}")


def test_submit_stores_exact_schema(client, mongo_db, mock_llm_url):
    rid = start(client)
    upload(client, rid, pcm_from_wav("quick_brown_fox.wav"))
    r = client.post(f"/recordings/{rid}/submit", json={})
    assert r.status_code == 201, r.text
    doc = r.json()
    assert {"quick", "brown", "fox", "lazy", "dog"} <= words(doc["transcript"])
    assert doc["summary"].startswith("SUMMARY[mock-summarizer]:")
    assert "summaryError" not in doc

    stored = mongo_db[COLLECTION].find_one({"_id": ObjectId(doc["id"])})
    assert list(stored.keys()) == ["_id", "timestamp", "transcript", "language", "summary", "user", "device"]
    assert list(stored["user"].keys()) == ["username", "roles"]
    assert list(stored["device"].keys()) == ["clientAddress", "userAgent", "sessionType"]
    assert re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", stored["timestamp"])
    assert stored["transcript"] == doc["transcript"]
    assert stored["language"] == "en"
    assert stored["user"]["username"] == client.username
    assert isinstance(stored["user"]["roles"], list) and stored["user"]["roles"]
    assert stored["device"]["sessionType"] == "Gateway API"
    assert stored["device"]["userAgent"].startswith("python-requests")

    # The gateway sent the decrypted API key and the rendered system prompt to the LLM.
    last = requests.get(mock_llm_url + "/v1/_last", timeout=5).json()
    assert last["authorized"] is True
    system = last["request"]["messages"][0]["content"]
    assert system == f"Summarize for {client.username} ({', '.join(stored['user']['roles'])}) in en."
    assert last["request"]["messages"][1]["content"] == doc["transcript"]

    # Recording is gone after a successful submit.
    assert client.post(f"/recordings/{rid}/stop").status_code == 404


def test_operator_edits_replace_transcript(client, mongo_db):
    rid = start(client)
    upload(client, rid, pcm_from_wav("quick_brown_fox.wav"))
    client.post(f"/recordings/{rid}/stop")
    edited = "The quick brown fox jumps over the lazy dog (edited by operator)."
    doc = client.post(f"/recordings/{rid}/submit", json={"transcript": edited}).json()
    assert doc["transcript"] == edited
    assert mongo_db[COLLECTION].find_one({"_id": ObjectId(doc["id"])})["transcript"] == edited


def test_submit_without_summary(client, mongo_db):
    rid = start(client)
    upload(client, rid, pcm_from_wav("quick_brown_fox.wav"))
    doc = client.post(f"/recordings/{rid}/submit", json={"summarize": False}).json()
    assert doc["summary"] == ""
    assert mongo_db[COLLECTION].find_one({"_id": ObjectId(doc["id"])})["summary"] == ""


def test_llm_outage_still_stores_transcript(client, mongo_db):
    client.put("/config", json={"llm": {"endpointUrl": "http://mock-llm:8000/broken", "apiKey": {"mode": "keep"}}})
    try:
        rid = start(client)
        upload(client, rid, pcm_from_wav("quick_brown_fox.wav"))
        r = client.post(f"/recordings/{rid}/submit", json={})
        assert r.status_code == 201
        doc = r.json()
        assert doc["summary"] == "" and "404" in doc["summaryError"]
        assert mongo_db[COLLECTION].find_one({"_id": ObjectId(doc["id"])}) is not None
    finally:
        client.put("/config", json=module_settings())


def test_wav_upload_is_accepted(client):
    rid = start(client)
    with open(FIXTURES / "quick_brown_fox.wav", "rb") as f:
        r = client.post(f"/recordings/{rid}/chunks", data=f.read(), headers={"Content-Type": "audio/wav"})
    assert r.status_code == 200, r.text
    assert {"quick", "fox"} <= words(r.json()["partialTranscript"])
    client.delete(f"/recordings/{rid}")


def test_duplicate_chunk_is_ignored(client):
    rid = start(client)
    pcm = pcm_from_wav("quick_brown_fox.wav")[:CHUNK]
    first = client.post(f"/recordings/{rid}/chunks", data=pcm, headers={**L16, "X-Chunk-Seq": "0"}).json()
    again = client.post(f"/recordings/{rid}/chunks", data=pcm, headers={**L16, "X-Chunk-Seq": "0"}).json()
    assert again["duplicate"] is True
    assert again["durationSeconds"] == first["durationSeconds"]
    client.delete(f"/recordings/{rid}")


def test_bad_audio_is_rejected(client):
    rid = start(client)
    r = client.post(f"/recordings/{rid}/chunks", data=b"\x1aE\xdf\xa3", headers={"Content-Type": "audio/webm"})
    assert r.status_code == 415
    r = client.post(f"/recordings/{rid}/chunks", data=b"\x01\x02\x03", headers=L16)
    assert r.status_code == 415
    client.delete(f"/recordings/{rid}")


def test_silence_is_not_transcribed(client):
    """Whisper hallucinates on silence ("you you you"); the gateway must not pass silent audio to it."""
    rid = start(client)
    silence = bytes(CHUNK)
    for seq in range(3):
        r = client.post(f"/recordings/{rid}/chunks", data=silence, headers={**L16, "X-Chunk-Seq": str(seq)}).json()
        assert r["silent"] is True
        assert r["transcribed"] is False
        assert r.get("partialTranscript", "") == ""
    assert client.post(f"/recordings/{rid}/stop").json()["transcript"] == ""
    r = client.post(f"/recordings/{rid}/submit", json={})
    assert r.status_code == 422
    assert "No speech" in r.json()["error"]
    client.delete(f"/recordings/{rid}")


def test_speech_after_silence_is_kept(client):
    rid = start(client)
    client.post(f"/recordings/{rid}/chunks", data=bytes(CHUNK), headers={**L16, "X-Chunk-Seq": "0"})
    speech = pcm_from_wav("quick_brown_fox.wav")
    r = client.post(f"/recordings/{rid}/chunks", data=speech, headers={**L16, "X-Chunk-Seq": "1"}).json()
    assert r["silent"] is False
    assert {"quick", "fox"} <= words(client.post(f"/recordings/{rid}/stop").json()["transcript"])
    client.delete(f"/recordings/{rid}")


def test_mixed_sources_are_labelled_in_time_order(client, mongo_db):
    """Two tracks are transcribed separately and merged by time: the video plays first, then the operator talks."""
    r = client.post("/recordings", json={"tracks": ["microphone", "system"]})
    assert r.status_code == 201, r.text
    assert r.json()["tracks"] == ["microphone", "system"]
    rid = r.json()["recordingId"]

    system = pcm_from_wav("quick_brown_fox.wav")                    # 0 - 3.7 s
    mic = bytes(16000 * 2 * 5) + pcm_from_wav("pump_report.wav")[:16000 * 2 * 7]   # silent 5 s, then speech
    for seq, i in enumerate(range(0, len(system), CHUNK)):
        client.post(f"/recordings/{rid}/chunks", data=system[i:i + CHUNK],
                    headers={**L16, "X-Chunk-Seq": str(seq), "X-Audio-Track": "system"})
    last = None
    for seq, i in enumerate(range(0, len(mic), CHUNK)):
        last = client.post(f"/recordings/{rid}/chunks", data=mic[i:i + CHUNK],
                           headers={**L16, "X-Chunk-Seq": str(seq), "X-Audio-Track": "microphone"}).json()
    assert last["track"] == "microphone"

    stop = client.post(f"/recordings/{rid}/stop").json()
    lines = stop["transcript"].split("\n")
    assert lines[0].startswith("[System audio] ") and {"quick", "fox"} <= words(lines[0]), stop["transcript"]
    assert lines[1].startswith("[Microphone] ") and {"shift", "pump"} <= words(lines[1]), stop["transcript"]
    assert [t["source"] for t in stop["turns"]] == ["system", "microphone"]
    assert stop["turns"][0]["label"] == "System audio"

    doc = client.post(f"/recordings/{rid}/submit", json={"summarize": False}).json()
    assert mongo_db[COLLECTION].find_one({"_id": ObjectId(doc["id"])})["transcript"] == stop["transcript"]


def test_timestamped_mixed_transcript(client):
    """timestamps=true: one "[mm:ss] [Source] text" line per segment, times relative to the recording start."""
    r = client.post("/recordings", json={"tracks": ["microphone", "system"], "timestamps": True})
    rid = r.json()["recordingId"]
    system = pcm_from_wav("quick_brown_fox.wav")
    mic = bytes(16000 * 2 * 6) + pcm_from_wav("pump_report.wav")[:16000 * 2 * 8]
    for seq, i in enumerate(range(0, len(system), CHUNK)):
        client.post(f"/recordings/{rid}/chunks", data=system[i:i + CHUNK],
                    headers={**L16, "X-Chunk-Seq": str(seq), "X-Audio-Track": "system"})
    for seq, i in enumerate(range(0, len(mic), CHUNK)):
        client.post(f"/recordings/{rid}/chunks", data=mic[i:i + CHUNK],
                    headers={**L16, "X-Chunk-Seq": str(seq), "X-Audio-Track": "microphone"})
    stop = client.post(f"/recordings/{rid}/stop").json()
    lines = stop["transcript"].split("\n")
    pattern = re.compile(r"^\[(\d\d):(\d\d)\] \[(Microphone|System audio)\] \S")
    assert all(pattern.match(ln) for ln in lines), stop["transcript"]
    seconds = [int(pattern.match(ln).group(1)) * 60 + int(pattern.match(ln).group(2)) for ln in lines]
    assert seconds == sorted(seconds)
    assert lines[0].startswith("[00:00] [System audio]")
    first_mic = next(s for s, ln in zip(seconds, lines) if "[Microphone]" in ln)
    assert 4 <= first_mic <= 7, stop["transcript"]   # speech starts 6 s in (0.2 s pre-roll, segment rounding)
    assert all("start" in t for t in stop["turns"])
    client.delete(f"/recordings/{rid}")


def test_track_validation(client):
    assert client.post("/recordings", json={"tracks": ["speaker"]}).status_code == 400
    assert client.post("/recordings", json={"tracks": ["system", "system"]}).status_code == 400
    rid = start(client, tracks=["microphone", "system"])
    r = client.post(f"/recordings/{rid}/chunks", data=bytes(CHUNK), headers={**L16, "X-Audio-Track": "camera"})
    assert r.status_code == 400
    client.delete(f"/recordings/{rid}")


def test_single_source_transcript_is_unlabelled(client):
    rid = start(client)
    upload(client, rid, pcm_from_wav("quick_brown_fox.wav"))
    transcript = client.post(f"/recordings/{rid}/stop").json()["transcript"]
    assert not transcript.startswith("[")
    client.delete(f"/recordings/{rid}")


def transcribe(client, pcm: bytes, **start) -> str:
    rid = start_recording = client.post("/recordings", json=start).json()["recordingId"]
    upload(client, start_recording, pcm)
    text = client.post(f"/recordings/{rid}/stop").json()["transcript"]
    client.delete(f"/recordings/{rid}")
    return text


@pytest.mark.parametrize("kind", ["pink", "hum", "clicks"])
@pytest.mark.parametrize("dbfs", [-45, -35, -25])
def test_background_noise_is_not_transcribed(client, kind, dbfs):
    """Before the noise filter these produced "Thank you.", "you you", "Thanks for watching." etc."""
    assert transcribe(client, getattr(noise, kind)(12, dbfs)) == ""


def test_speech_over_noise_keeps_only_the_speech(client):
    fox = pcm_from_wav("quick_brown_fox.wav")
    noisy = noise.mix(bytes(32000 * 4) + fox + bytes(32000 * 4), noise.pink(12, -35))
    text = transcribe(client, noisy)
    got = words(text)
    assert {"quick", "brown", "jumps", "lazy", "dog"} <= got, text
    # Before: "... lazy dog. . Trust me, you should believe it, you know? you"
    assert not got & {"trust", "believe", "know", "you"}, text


def test_noise_filter_off_is_raw_whisper(client):
    client.put("/config", json={"whisper": {"noiseFilter": "off"}, "llm": {"apiKey": {"mode": "keep"}}})
    try:
        assert client.get("/config").json()["whisper"]["noiseFilter"] == "off"
        assert transcribe(client, noise.pink(12, -35)) != ""   # Whisper hallucinates on noise
    finally:
        client.put("/config", json=module_settings())


def test_timestamps_continue_from_offset(client):
    """A recording appended to an existing log passes timeOffsetSeconds so the clock keeps running."""
    text = transcribe(client, pcm_from_wav("quick_brown_fox.wav"), timestamps=True, timeOffsetSeconds=65)
    assert text.startswith("[01:05] "), text


def test_cancel_discards_recording(client):
    rid = start(client)
    assert client.delete(f"/recordings/{rid}").status_code == 200
    assert client.post(f"/recordings/{rid}/stop").status_code == 404


def test_unknown_recording_is_404(client):
    assert client.post("/recordings/nope/chunks", data=b"\x00\x00", headers=L16).status_code == 404


def test_auto_detect_language(client, mongo_db):
    client.put("/config", json={"whisper": {"autoDetect": True}, "llm": {"apiKey": {"mode": "keep"}}})
    try:
        rid = start(client)
        assert client.get("/client-config").json()["autoDetect"] is True
        upload(client, rid, pcm_from_wav("pump_report.wav"))
        doc = client.post(f"/recordings/{rid}/submit", json={"summarize": False}).json()
        assert doc["language"] == "en"
    finally:
        client.put("/config", json=module_settings())


def test_recent_and_lookup_routes(client):
    recent = client.get("/transcripts", params={"limit": 3}).json()["transcripts"]
    assert 1 <= len(recent) <= 3
    stamps = [t["timestamp"] for t in recent]
    assert stamps == sorted(stamps, reverse=True)
    one = client.get(f"/transcripts/{recent[0]['id']}").json()
    assert one["id"] == recent[0]["id"]
    assert client.get("/transcripts/000000000000000000000000").status_code == 404


def test_retention_purges_old_documents(client, mongo_db):
    old = mongo_db[COLLECTION].insert_one({
        "timestamp": "2020-01-01T00:00:00Z", "transcript": "old", "language": "en", "summary": "",
        "user": {"username": "x", "roles": []},
        "device": {"clientAddress": "", "userAgent": "", "sessionType": "Gateway API"},
    }).inserted_id
    keep_count = mongo_db[COLLECTION].count_documents({"timestamp": {"$gte": "2021"}})
    client.put("/config", json={"whisper": {"retentionDays": 30}, "llm": {"apiKey": {"mode": "keep"}}})
    try:
        deadline = time.time() + 20
        while time.time() < deadline and mongo_db[COLLECTION].find_one({"_id": old}):
            time.sleep(1)
        assert mongo_db[COLLECTION].find_one({"_id": old}) is None
        assert mongo_db[COLLECTION].count_documents({"timestamp": {"$gte": "2021"}}) == keep_count
    finally:
        client.put("/config", json=module_settings())


def test_recordings_are_isolated_per_caller(client, fresh_client):
    """Another caller can't append to or submit someone else's recording."""
    rid = start(client)
    # Same user over a different gateway session still maps to the same API owner key, so prove isolation
    # through the Perspective path instead: a forged session header never reaches the recording.
    r = fresh_client.post(f"/recordings/{rid}/chunks", data=b"\x00\x00",
                          headers={**L16, "X-Perspective-Session-Id": "forged", "X-Perspective-Project": "x"})
    assert r.status_code == 403
    client.delete(f"/recordings/{rid}")
