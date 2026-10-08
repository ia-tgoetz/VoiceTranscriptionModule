"""Deterministic OpenAI-compatible stub used by the test stack.

GET  /v1/models            -> {"data": [{"id": "mock-summarizer"}, {"id": "mock-large"}, {"id": "text-embedding-mock"}]}
                              (the embedding model must be filtered out of the settings page's chat-model list)
POST /v1/chat/completions  -> summary that echoes enough of the request for assertions:
                              "SUMMARY[<model>]: <first 60 chars of the transcript>"
Requests without "Authorization: Bearer <MOCK_LLM_KEY>" get 401, so the test-suite proves the gateway
decrypted the API key from the Secrets API and sent it. The last request is kept at GET /v1/_last.
"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

API_KEY = os.environ.get("MOCK_LLM_KEY", "test-llm-key")
LAST = {}


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body):
        data = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self):
        return self.headers.get("Authorization") == f"Bearer {API_KEY}"

    def do_GET(self):
        if self.path == "/v1/_last":
            return self._send(200, LAST)
        if self.path == "/v1/models":
            if not self._authorized():
                return self._send(401, {"error": {"message": "bad key"}})
            return self._send(200, {"object": "list", "data": [{"id": "mock-summarizer"}, {"id": "mock-large"},
                                                              {"id": "text-embedding-mock"}]})
        return self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            return self._send(404, {"error": "not found"})
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        LAST.clear()
        LAST.update({"authorized": self._authorized(), "request": body})
        if not self._authorized():
            return self._send(401, {"error": {"message": "bad key"}})
        user = next((m["content"] for m in body.get("messages", []) if m.get("role") == "user"), "")
        summary = f"SUMMARY[{body.get('model')}]: {user[:60]}"
        return self._send(200, {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "model": body.get("model"),
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": summary}}],
        })

    def log_message(self, fmt, *args):  # keep container logs quiet
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
