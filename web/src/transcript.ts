/**
 * Transcript-box text model (pure, no DOM - unit-tested with `node --test`).
 *
 * The box is a running log: recordings append, nothing clears it except an explicit Clear (or a binding /
 * script writing props.transcript). After each submit a divider line is appended; Submit only sends the
 * text below the last divider, so a log submitted twice never stores the same lines twice.
 */

export const MARKER_PREFIX = "──── Submitted ";
const MARKER_RE = /^─{4} Submitted .* ─{4}$/;
const LINE_RE = /^(\[\d{1,2}:\d{2}(?::\d{2})?\] )?(\[(Microphone|System audio)\] )?/;

export type Part =
    | { kind: "timestamp"; text: string }
    | { kind: "label"; text: string; source: "microphone" | "system" }
    | { kind: "marker"; text: string }
    | { kind: "text"; text: string };

/** "──── Submitted 14:02:11 ────" */
export function marker(at: Date): string {
    const pad = (n: number) => String(n).padStart(2, "0");
    return `${MARKER_PREFIX}${pad(at.getHours())}:${pad(at.getMinutes())}:${pad(at.getSeconds())} ────`;
}

export function isMarker(line: string): boolean {
    return MARKER_RE.test(line.trim());
}

/** Text below the last submit divider (what Submit will send). */
export function pendingText(text: string): string {
    const lines = text.split("\n");
    let start = 0;
    lines.forEach((line, i) => {
        if (isMarker(line)) {
            start = i + 1;
        }
    });
    return lines.slice(start).join("\n").trim();
}

/** Appends `addition` on a new line below `base` (either may be empty). */
export function compose(base: string, addition: string): string {
    const b = base.replace(/\s+$/, "");
    const a = addition.replace(/^\s+/, "");
    if (!b) {
        return a;
    }
    return a ? `${b}\n${a}` : b;
}

/** Splits one line into coloured parts. Characters are never added or removed (overlay alignment). */
export function parseLine(line: string): Part[] {
    if (isMarker(line)) {
        return [{ kind: "marker", text: line }];
    }
    const m = LINE_RE.exec(line);
    const parts: Part[] = [];
    let rest = line;
    if (m && m[1]) {
        parts.push({ kind: "timestamp", text: m[1] });
        rest = rest.slice(m[1].length);
    }
    if (m && m[2]) {
        parts.push({ kind: "label", text: m[2], source: m[3] === "Microphone" ? "microphone" : "system" });
        rest = rest.slice(m[2].length);
    }
    if (rest) {
        parts.push({ kind: "text", text: rest });
    }
    return parts;
}
