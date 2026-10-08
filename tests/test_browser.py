"""Real-browser tests (headless Chromium-family browser via Playwright).

* Perspective: Chromium's fake-media flags feed a fixture WAV into getUserMedia, so the Voice Recorder
  component exercises the real WebRTC -> MediaRecorder -> decode -> PCM upload path inside a logged-in
  Perspective session, and the stored document proves username/roles came from that session.
* Gateway web UI: the React settings page loads through SystemJS and saves with the CSRF token.

Requires `pip install playwright` and a local Edge/Chrome (PW_BROWSER_CHANNEL, default "msedge"; use
"chromium" after `playwright install chromium`). The VoiceDemo project must be mounted (docker-compose does
this) and use the "default" Identity Provider; the suite sets that through the gateway Projects API.
"""
import base64
import os
import re
import shutil

import pytest
from bson import ObjectId

from conftest import COLLECTION, FIXTURES

playwright_api = pytest.importorskip("playwright.sync_api")
CHANNEL = os.environ.get("PW_BROWSER_CHANNEL", "msedge")
PROJECT = os.environ.get("VT_DEMO_PROJECT", "VoiceDemo")


@pytest.fixture(scope="module")
def pw(client):
    """One Playwright instance per module (the sync API can't be nested)."""
    r = client.request("PUT", f"/data/api/v1/projects/{PROJECT}", json={"identityProvider": "default"}, base="")
    if r.status_code == 404:
        pytest.skip(f"Perspective project {PROJECT} is not on this gateway")
    with playwright_api.sync_playwright() as p:
        yield p


@pytest.fixture(scope="module")
def browser(tmp_path_factory, pw):
    """Browser whose microphone is a fake device playing the fox fixture."""
    # Chromium's --use-file-for-fake-audio-capture can't take a path with spaces; copy the fixture first.
    wav = tmp_path_factory.mktemp("audio") / "fox.wav"
    shutil.copy(FIXTURES / "quick_brown_fox.wav", wav)
    b = launch(pw, "--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream",
               f"--use-file-for-fake-audio-capture={wav.as_posix()}")
    yield b
    b.close()


def launch(playwright, *args, mute=True):
    try:
        return playwright.chromium.launch(
            channel=None if CHANNEL == "chromium" else CHANNEL, headless=True, args=list(args),
            # Playwright mutes headless browsers by default, which also silences tab-capture audio.
            ignore_default_args=[] if mute else ["--mute-audio"])
    except Exception as e:  # browser not installed
        pytest.skip(f"No {CHANNEL} browser available: {e}")


def player_html(title: str, wav: str | None) -> str:
    """A tab that plays a fixture in a loop (or nothing, when wav is None)."""
    if wav is None:
        return f"<html><head><title>{title}</title></head><body>silent tab</body></html>"
    data = base64.b64encode((FIXTURES / wav).read_bytes()).decode()
    return (f"<html><head><title>{title}</title></head><body>"
            f"<audio id=a src='data:audio/wav;base64,{data}' loop></audio>"
            "<script>document.getElementById('a').play()</script></body></html>")


def open_recorder(context, client, source: str):
    page = context.new_page()
    page.goto(f"{client.base}/data/perspective/client/{PROJECT}/source/{source}")
    page.get_by_text(re.compile("continue to log in", re.I)).click()
    idp_login(page, client)
    page.wait_for_selector(".vt-recorder", timeout=30000)
    return page


def idp_login(page, client):
    page.wait_for_selector("input[name=username]", timeout=20000)
    page.fill("input[name=username]", client.username)
    page.keyboard.press("Enter")
    page.wait_for_selector("input[name=password]", timeout=20000)
    page.fill("input[name=password]", client.password)
    page.keyboard.press("Enter")


def test_perspective_recorder_push_to_talk(browser, client, mongo_db):
    page = browser.new_page()
    page.goto(f"{client.base}/data/perspective/client/{PROJECT}")
    page.get_by_text(re.compile("continue to log in", re.I)).click()
    idp_login(page, client)
    page.wait_for_selector(".vt-recorder", timeout=30000)

    # Hold Space (push-to-talk) while the fake microphone plays the fixture; partials stream in meanwhile.
    page.keyboard.down("Space")
    page.wait_for_function(
        "document.querySelector('.vt-recorder__transcript').value.toLowerCase().includes('fox')", timeout=20000)
    page.keyboard.up("Space")
    page.wait_for_function(
        "document.querySelector('.vt-recorder__status').innerText.startsWith('Edit if needed')", timeout=30000)

    edited = page.input_value(".vt-recorder__transcript") + " (checked by operator)"
    page.fill(".vt-recorder__transcript", edited)
    page.click(".vt-recorder__button--primary")
    page.wait_for_selector(".vt-recorder__summary", timeout=30000)
    assert "SUMMARY[mock-summarizer]" in page.inner_text(".vt-recorder__summary")
    page.wait_for_selector("text=Status: submitted", timeout=10000)  # props.status bound to a label

    doc = mongo_db[COLLECTION].find_one({"transcript": edited})
    assert doc is not None, "browser submission not found in MongoDB"
    assert doc["user"]["username"] == client.username
    assert doc["user"]["roles"], "roles should come from the authenticated Perspective session"
    assert doc["device"]["sessionType"] == "Perspective Web"
    assert "Edg/" in doc["device"]["userAgent"] or "Chrome/" in doc["device"]["userAgent"]
    assert isinstance(doc["_id"], ObjectId)
    page.close()


def test_gateway_settings_page(browser, client):
    page = browser.new_page()
    page.goto(f"{client.base}/data/app/login")
    idp_login(page, client)
    page.wait_for_url(re.compile(r".*/app(/.*)?$"), timeout=30000)  # login redirect chain finished
    page.goto(f"{client.base}/app/services/voice-transcription")
    page.wait_for_selector("text=Offline Voice Transcription", timeout=30000)
    page.wait_for_selector("text=MongoDB Connector", timeout=10000)

    connector = page.locator(".vt-card select").first
    assert connector.input_value() == "VoiceMongo"
    page.get_by_role("button", name="Browse").click()
    page.wait_for_selector("text=collection(s) via 'VoiceMongo'", timeout=15000)

    page.get_by_role("button", name="Save settings").click()  # PUT with X-CSRF-Token
    page.wait_for_selector("text=Settings saved.", timeout=20000)
    assert "Whisper: ready" in page.inner_text(".vt-status")

    # Model list from the provider as entered in the form (POST /llm/models), embedding models filtered out.
    page.get_by_role("button", name="Load models").click()
    page.wait_for_selector("text=Loaded 2 chat model(s)", timeout=15000)
    model = page.locator("select:has(option[value=mock-large])")
    assert model.input_value() == "mock-summarizer"
    assert "text-embedding-mock" not in model.inner_text()

    # Switching provider without a key for it asks for the key instead of sending the saved one elsewhere.
    page.locator("select:has(option[value=gemini])").select_option("openai")
    page.wait_for_selector("text=Enter the OpenAI API key below", timeout=5000)
    assert page.locator("input[value='https://api.openai.com/v1']").count() == 1
    page.close()  # unsaved - the stored settings are untouched


def test_perspective_records_browser_tab_audio(pw, client, mongo_db):
    """audioSource=tab: getDisplayMedia shares another tab (auto-selected by title) and its audio is transcribed."""
    b = launch(pw, "--autoplay-policy=no-user-gesture-required",
               "--auto-select-tab-capture-source-by-title=VtPlayerTab", mute=False)
    ctx = b.new_context()
    ctx.new_page().set_content(player_html("VtPlayerTab", "quick_brown_fox.wav"))
    page = open_recorder(ctx, client, "tab")
    assert "choose a tab" in page.inner_text(".vt-recorder__status")

    page.click(".vt-recorder__mic")                       # opens the share picker -> auto-selects the tab
    page.wait_for_selector(".vt-recorder__share", timeout=15000)
    page.wait_for_function(
        "document.querySelector('.vt-recorder__transcript').value.toLowerCase().includes('fox')", timeout=30000)
    page.click(".vt-recorder__mic")
    page.wait_for_function(
        "document.querySelector('.vt-recorder__status').innerText.startsWith('Edit if needed')", timeout=30000)
    transcript = page.input_value(".vt-recorder__transcript")
    assert "lazy dog" in transcript.lower()

    # The share stays open for the next recording; "Stop sharing" releases it.
    assert page.is_visible(".vt-recorder__share")
    page.click(".vt-recorder__button--primary")
    page.wait_for_selector(".vt-recorder__summary", timeout=30000)
    page.click("text=Stop sharing")
    page.wait_for_selector(".vt-recorder__share", state="detached", timeout=5000)
    b.close()
    assert mongo_db[COLLECTION].find_one({"transcript": transcript}) is not None


def test_silent_tab_warns_instead_of_hallucinating(pw, client):
    b = launch(pw, "--auto-select-tab-capture-source-by-title=VtSilentTab", mute=False)
    ctx = b.new_context()
    ctx.new_page().set_content(player_html("VtSilentTab", None))
    page = open_recorder(ctx, client, "tab")
    page.click(".vt-recorder__mic")
    page.wait_for_selector(".vt-recorder__warning", timeout=20000)
    assert "No sound from the shared tab" in page.inner_text(".vt-recorder__warning")
    assert page.input_value(".vt-recorder__transcript") == ""
    page.click(".vt-recorder__mic")
    page.wait_for_function(
        "document.querySelector('.vt-recorder__status').innerText.startsWith('Edit if needed')", timeout=30000)
    assert page.input_value(".vt-recorder__transcript") == ""
    b.close()


def test_mixed_mode_labels_microphone_and_tab(pw, tmp_path_factory, client):
    """mixed: the fake microphone says the fox sentence while another tab plays the pump report."""
    wav = tmp_path_factory.mktemp("mic") / "fox.wav"
    shutil.copy(FIXTURES / "quick_brown_fox.wav", wav)
    b = launch(pw, "--autoplay-policy=no-user-gesture-required",
               "--use-fake-device-for-media-stream", f"--use-file-for-fake-audio-capture={wav.as_posix()}",
               "--auto-select-tab-capture-source-by-title=VtPumpTab", mute=False)
    ctx = b.new_context()
    ctx.grant_permissions(["microphone"])
    ctx.new_page().set_content(player_html("VtPumpTab", "pump_report.wav"))
    page = open_recorder(ctx, client, "mixed")

    page.click(".vt-recorder__mic")
    page.wait_for_selector(".vt-recorder__share", timeout=15000)
    page.wait_for_function(
        "(() => { const v = document.querySelector('.vt-recorder__transcript').value;"
        " return v.includes('[Microphone]') && v.includes('[System audio]'); })()", timeout=40000)
    page.click(".vt-recorder__mic")
    page.wait_for_function(
        "document.querySelector('.vt-recorder__status').innerText.startsWith('Edit if needed')", timeout=40000)

    lines = [ln for ln in page.input_value(".vt-recorder__transcript").split("\n") if ln.strip()]
    line = re.compile(r"^\[\d\d:\d\d\] \[(Microphone|System audio)\] ")   # showTimestamps defaults on
    assert all(line.match(ln) for ln in lines), lines
    mic_text = " ".join(ln for ln in lines if "[Microphone]" in ln).lower()
    sys_text = " ".join(ln for ln in lines if "[System audio]" in ln).lower()
    # Attribution, not ASR accuracy: the fake mic loops the fox clip (tiny may hear "box"), the tab plays the
    # pump report. Each source's words must land on its own lines only.
    fox_words = ("fox", "box", "quick", "brown", "lazy", "dog", "jumps")
    pump_words = ("pump", "shift", "bearing", "maintenance", "degrees", "tank", "safety")
    assert any(w in mic_text for w in fox_words) and not any(w in mic_text for w in pump_words), lines
    assert any(w in sys_text for w in pump_words) and not any(w in sys_text for w in fox_words), lines
    # Colour-coded labels: two distinct colours from the defaults.
    mic_color = page.eval_on_selector(".vt-tx--microphone", "e => getComputedStyle(e).color")
    sys_color = page.eval_on_selector(".vt-tx--system", "e => getComputedStyle(e).color")
    assert mic_color == "rgb(29, 111, 216)" and sys_color == "rgb(194, 65, 12)", (mic_color, sys_color)
    b.close()


def box(page) -> str:
    return page.input_value(".vt-recorder__transcript")


def record_once(page):
    before = box(page)
    page.click(".vt-recorder__mic")
    page.wait_for_function(
        "b => document.querySelector('.vt-recorder__transcript').value.length > b", arg=len(before), timeout=30000)
    page.click(".vt-recorder__mic")
    page.wait_for_function(
        "document.querySelector('.vt-recorder__status').innerText.startsWith('Edit if needed')", timeout=30000)


def test_transcript_box_keeps_text_colours_it_and_submits_only_new_lines(browser, client, mongo_db):
    ctx = browser.new_context()
    page = open_recorder(ctx, client, "microphone")

    # 1) Two recordings accumulate; nothing is cleared between them.
    record_once(page)
    first = box(page)
    assert re.match(r"^\[\d\d:\d\d\] ", first), first
    record_once(page)
    both = box(page)
    assert both.startswith(first) and len(both) > len(first), both
    stamps = [int(m[0]) * 60 + int(m[1]) for m in re.findall(r"^\[(\d\d):(\d\d)\]", both, re.M)]
    assert stamps == sorted(stamps) and stamps[-1] > stamps[0], both   # clock keeps running across recordings

    # 2) The coloured backdrop renders exactly the textarea's characters.
    backdrop = page.eval_on_selector(".vt-recorder__backdrop", "e => e.textContent").rstrip("\u200b")
    assert backdrop == both
    ts_color = page.eval_on_selector(".vt-tx--timestamp", "e => getComputedStyle(e).color")
    assert ts_color == "rgb(107, 114, 128)", ts_color
    assert page.eval_on_selector(".vt-recorder__transcript", "e => getComputedStyle(e).color") == "rgba(0, 0, 0, 0)"

    # 3) Submit stores the log, appends a divider, keeps the text, and has nothing new to submit.
    page.click(".vt-recorder__button--primary")
    page.wait_for_selector("text=Status: submitted", timeout=30000)
    after_submit = box(page)
    assert after_submit.startswith(both)
    assert re.search(r"^──── Submitted \d\d:\d\d:\d\d ────$", after_submit, re.M), after_submit
    assert page.is_disabled(".vt-recorder__button--primary")
    assert mongo_db[COLLECTION].find_one({"transcript": both.strip()}) is not None
    assert page.eval_on_selector(".vt-tx--marker", "e => e.textContent").startswith("──── Submitted")

    # 4) Record again: only the new lines (below the divider) are submitted.
    record_once(page)
    new_text = box(page).split("────\n", 1)[-1].strip()
    assert new_text and new_text not in both
    page.click(".vt-recorder__button--primary")
    page.wait_for_function(
        "document.querySelector('.vt-recorder__transcript').value.trim().endsWith('────')", timeout=30000)
    assert mongo_db[COLLECTION].find_one({"transcript": new_text}) is not None

    # 5) Clear needs a confirmation click; only then is the box emptied.
    page.click("text=Clear")
    assert box(page) != "" and page.is_visible("text=Confirm clear")
    page.click("text=Confirm clear")
    page.wait_for_function("document.querySelector('.vt-recorder__transcript').value === ''", timeout=5000)
    ctx.close()
