import { test } from "node:test";
import assert from "node:assert/strict";
import { compose, isMarker, marker, parseLine, pendingText } from "../src/transcript.ts";

test("parseLine colours timestamp and source label without changing characters", () => {
    const line = "[00:05] [Microphone] Checking pump two.";
    const parts = parseLine(line);
    assert.deepEqual(parts.map((p) => p.kind), ["timestamp", "label", "text"]);
    assert.equal(parts.map((p) => p.text).join(""), line);
    assert.equal((parts[1] as { source: string }).source, "microphone");
    assert.equal((parseLine("[System audio] video")[0] as { source: string }).source, "system");
    assert.deepEqual(parseLine("plain words").map((p) => p.kind), ["text"]);
    assert.deepEqual(parseLine("[1:02:03] long").map((p) => p.kind), ["timestamp", "text"]);
    assert.deepEqual(parseLine("").map((p) => p.kind), []);
});

test("marker lines", () => {
    const m = marker(new Date(2026, 9, 8, 14, 2, 11));
    assert.equal(m, "──── Submitted 14:02:11 ────");
    assert.ok(isMarker(m));
    assert.deepEqual(parseLine(m).map((p) => p.kind), ["marker"]);
    assert.ok(!isMarker("[00:01] ──── Submitted"));
});

test("pendingText returns only what is below the last divider", () => {
    const m1 = marker(new Date(2026, 9, 8, 9, 0, 0));
    const m2 = marker(new Date(2026, 9, 8, 9, 5, 0));
    assert.equal(pendingText("a\nb"), "a\nb");
    assert.equal(pendingText(`a\n${m1}\nb\nc`), "b\nc");
    assert.equal(pendingText(`a\n${m1}\nb\n${m2}\n`), "");
    assert.equal(pendingText(`a\n${m1}\nb\n${m2}\n  d  `), "d");
});

test("compose appends on a new line and never drops the base", () => {
    assert.equal(compose("", "new"), "new");
    assert.equal(compose("old\n\n", "new"), "old\nnew");
    assert.equal(compose("old", ""), "old");
    assert.equal(compose("", ""), "");
});
