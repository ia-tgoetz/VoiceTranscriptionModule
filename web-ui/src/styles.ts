/** Scoped styles for the settings page (kept inline so the bundle is a single SystemJS module). */
export const CSS = `
.vt-page { padding: 16px 24px; max-width: 1100px; font-size: 14px; }
.vt-page h1 { font-size: 22px; margin: 0 0 16px; }
.vt-card { border: 1px solid #d0d4da; border-radius: 6px; padding: 12px 16px; margin-bottom: 16px; background: #fff; }
.vt-card h2 { font-size: 16px; margin: 0 0 12px; display: flex; gap: 12px; align-items: center; }
.vt-row { display: flex; gap: 16px; flex-wrap: wrap; }
.vt-row > .vt-field { flex: 1 1 220px; }
.vt-field { display: flex; flex-direction: column; gap: 4px; margin-bottom: 12px; }
.vt-field > label { font-weight: 600; }
.vt-field input, .vt-field select, .vt-field textarea { padding: 6px 8px; border: 1px solid #b8bec7; border-radius: 4px; font: inherit; }
.vt-field textarea { font-family: ui-monospace, Consolas, monospace; }
.vt-inline { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.vt-inline > input { flex: 1 1 200px; }
.vt-check { display: flex; gap: 6px; align-items: center; font-weight: normal; }
.vt-hint { color: #5f6773; font-size: 12px; font-weight: normal; }
.vt-page button { padding: 6px 12px; border: 1px solid #b8bec7; border-radius: 4px; background: #f6f7f9; cursor: pointer; }
.vt-page button.vt-primary { background: #2c6ecb; border-color: #2c6ecb; color: #fff; }
.vt-page button:disabled { opacity: 0.6; cursor: default; }
.vt-actions { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; gap: 16px; }
.vt-msg { padding: 6px 10px; border-radius: 4px; }
.vt-msg--ok { background: #e7f5ea; color: #1d6b2f; }
.vt-msg--error { background: #fdecec; color: #a32020; }
.vt-msg--info { background: #eaf1fb; color: #1f4f8f; }
.vt-status ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 6px; }
.vt-dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 6px; vertical-align: middle; }
.vt-dot--ok { background: #2e9e4f; } .vt-dot--bad { background: #d23b3b; } .vt-dot--neutral { background: #a0a7b1; }
.vt-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.vt-table th, .vt-table td { text-align: left; border-bottom: 1px solid #e3e6ea; padding: 6px; vertical-align: top; }
`;
