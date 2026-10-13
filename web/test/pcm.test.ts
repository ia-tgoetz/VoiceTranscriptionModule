// Run with: npm test  (Node >= 22.6 strips TypeScript types natively)
import { test } from "node:test";
import assert from "node:assert/strict";
import { downmix, floatToInt16, formatDuration, isEditableTarget, resampleLinear, resolveChunkInterval } from "../src/audio/pcm.ts";

test("floatToInt16 clamps and scales", () => {
    const out = floatToInt16(new Float32Array([0, 1, -1, 2, -2, 0.5]));
    assert.deepEqual(Array.from(out), [0, 32767, -32768, 32767, -32768, 16384]);
});

test("resampleLinear 48k -> 16k keeps duration", () => {
    const input = new Float32Array(48000).fill(0.25);
    const out = resampleLinear(input, 48000, 16000);
    assert.equal(out.length, 16000);
    assert.ok(Math.abs(out[100] - 0.25) < 1e-6);
});

test("resampleLinear is identity at equal rates", () => {
    const input = new Float32Array([0.1, 0.2]);
    assert.equal(resampleLinear(input, 16000, 16000), input);
});

test("downmix averages channels", () => {
    const out = downmix([new Float32Array([1, 0]), new Float32Array([0, 1])]);
    assert.deepEqual(Array.from(out), [0.5, 0.5]);
});

test("formatDuration", () => {
    assert.equal(formatDuration(0), "0:00");
    assert.equal(formatDuration(65.7), "1:05");
});

test("push-to-talk ignores typing targets", () => {
    assert.equal(isEditableTarget({ tagName: "textarea" }), true);
    assert.equal(isEditableTarget({ tagName: "INPUT" }), true);
    assert.equal(isEditableTarget({ tagName: "DIV", isContentEditable: true }), true);
    assert.equal(isEditableTarget({ tagName: "DIV" }), false);
    assert.equal(isEditableTarget(null), false);
});

test("resolveChunkInterval: 0 defers to the gateway, overrides are clamped to 500-30000", () => {
    assert.equal(resolveChunkInterval(0, 3000), 3000);
    assert.equal(resolveChunkInterval(1500, 3000), 1500);
    assert.equal(resolveChunkInterval(100, 3000), 500);
    assert.equal(resolveChunkInterval(120000, 3000), 30000);
    assert.equal(resolveChunkInterval(0, 0), 500);
});
