"""Small client for an Ignition 8.3 gateway + the OfflineVoiceTranscription REST API.

Authentication uses a real gateway username/password, the same way a browser does:

1. GET  /data/app/login                      -> redirects to the Internal IdP's OIDC /auth endpoint
2. GET  /idp/<idp>/oidc/auth?...             -> redirects to /idp/<idp>/authn/login?...&token=<flow token>
3. POST /idp/<idp>/authn/submit-challenge/basic {token, rememberMe, challenge:{username,password}}
4. POST /idp/<idp>/authn/next-challenge     {token}
5. GET  /idp/<idp>/oidc/auth?...&token=<token> -> code -> /data/federate/callback/internal -> session cookie

After login, admin routes are authorised by the web-UI session; writes also need the session's CSRF token,
which the module exposes at GET /data/voicetranscription/api/v1/csrf.
"""
from __future__ import annotations

import os
from urllib.parse import parse_qs, urlencode, urljoin, urlparse

import requests

API = "/data/voicetranscription/api/v1"


class GatewayClient:
    def __init__(self, base_url: str, username: str, password: str, timeout: float = 30.0):
        self.base = base_url.rstrip("/")
        self.username = username
        self.password = password
        self.timeout = timeout
        self.http = requests.Session()
        self.csrf: dict[str, str] = {}

    # ------------------------------------------------------------------ login
    def login(self) -> "GatewayClient":
        r = self.http.get(self.base + "/data/app/login", allow_redirects=False, timeout=self.timeout)
        auth_url = urljoin(self.base, r.headers["Location"])
        r = self.http.get(auth_url, allow_redirects=False, timeout=self.timeout)
        login_url = urljoin(self.base, r.headers["Location"])
        parsed = urlparse(login_url)
        params = {k: v[0] for k, v in parse_qs(parsed.query).items()}
        token = params.pop("token")
        authn = parsed.path.rsplit("/login", 1)[0]  # /idp/default/authn

        res = self.http.post(f"{self.base}{authn}/submit-challenge/basic", timeout=self.timeout, json={
            "token": token, "rememberMe": False,
            "challenge": {"username": self.username, "password": self.password},
        }).json()
        if not res.get("success"):
            raise RuntimeError(f"Login rejected for {self.username!r}: {res}")
        token = res.get("token", token)
        nxt = self.http.post(f"{self.base}{authn}/next-challenge", json={"token": token}, timeout=self.timeout).json()
        token = nxt.get("token", token)

        # Resume the OIDC authorization request with the authenticated flow token.
        idp_base = authn.rsplit("/authn", 1)[0]
        auth_params = {k: v for k, v in params.items()}
        auth_params["token"] = token
        r = self.http.get(f"{self.base}{idp_base}/oidc/auth?{urlencode(auth_params)}", timeout=self.timeout)
        r.raise_for_status()
        status = self.http.get(self.base + API + "/status", timeout=self.timeout)
        if status.status_code != 200:
            raise RuntimeError(f"Login did not yield an authorised session (status route -> {status.status_code})")
        self.csrf = {}
        return self

    # ------------------------------------------------------------------ requests
    def _csrf_headers(self) -> dict[str, str]:
        if not self.csrf:
            c = self.http.get(self.base + API + "/csrf", timeout=self.timeout).json()
            self.csrf = {c.get("header") or "X-CSRF-Token": c.get("token", "")}
        return self.csrf

    def request(self, method: str, path: str, *, json=None, data=None, headers=None, params=None,
                base: str = API) -> requests.Response:
        h = dict(headers or {})
        if method.upper() != "GET":
            h.update(self._csrf_headers())
        return self.http.request(method, self.base + base + path, json=json, data=data, headers=h,
                                 params=params, timeout=self.timeout)

    def get(self, path: str, **kw) -> requests.Response:
        return self.request("GET", path, **kw)

    def post(self, path: str, **kw) -> requests.Response:
        return self.request("POST", path, **kw)

    def put(self, path: str, **kw) -> requests.Response:
        return self.request("PUT", path, **kw)

    def delete(self, path: str, **kw) -> requests.Response:
        return self.request("DELETE", path, **kw)


def from_env() -> GatewayClient:
    return GatewayClient(
        os.environ.get("GATEWAY_URL", "http://localhost:8090"),
        os.environ.get("GATEWAY_USERNAME", "admin"),
        os.environ.get("GATEWAY_PASSWORD", "password"),
    )
