package com.wargoetz.voicetranscription.gateway.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.inductiveautomation.ignition.common.auth.web.WebAuthUser;
import com.inductiveautomation.ignition.common.model.values.QualifiedValue;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.web.session.WebUiSession;
import com.inductiveautomation.perspective.common.api.PropertyType;
import com.inductiveautomation.perspective.gateway.property.PropertyTree;
import com.inductiveautomation.perspective.gateway.session.InternalSession;
import com.inductiveautomation.perspective.gateway.session.PerspectiveSessionCollection;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Resolves the {@link CallerIdentity} for a recorder request.
 *
 * <p><b>Perspective path.</b> The browser sends {@code X-Perspective-Session-Id} and
 * {@code X-Perspective-Project}. The gateway looks the session up inside the caller's own HTTP session (the
 * {@code perspective-session} attribute Perspective binds to the session cookie), and only accepts it if the
 * ids match. Username, roles, host, and user agent are then read from the live Perspective session - the
 * same values scripts see as {@code session.props.auth.user}, {@code session.props.host}, and
 * {@code session.props.device}. The custom headers also make the request CORS-preflighted, which blocks
 * cross-site request forgery against the cookie-authenticated routes.</p>
 *
 * <p><b>API path.</b> Callers authenticated with a gateway API token (or an admin web-UI session) are
 * accepted for scripted ingestion and the automated tests; their identity is the token/user name.</p>
 */
public final class IdentityResolver {
    public static final String HEADER_SESSION_ID = "X-Perspective-Session-Id";
    public static final String HEADER_PROJECT = "X-Perspective-Project";
    public static final String HEADER_API_TOKEN = "X-Ignition-API-Token";

    private static final LoggerEx log = LoggerEx.newBuilder().build("VoiceTranscription.Identity");

    private IdentityResolver() {
    }

    public static CallerIdentity resolve(RequestContext req) throws ApiException {
        HttpServletRequest http = req.getRequest();
        String sessionId = http.getHeader(HEADER_SESSION_ID);

        if (sessionId != null && !sessionId.isBlank()) {
            return fromPerspective(http, sessionId.trim(), http.getHeader(HEADER_PROJECT));
        }

        boolean apiToken = http.getHeader(HEADER_API_TOKEN) != null;
        Optional<? extends WebUiSession> webUi = WebUiSession.find(req);
        if (!apiToken && webUi.isEmpty()) {
            throw new ApiException(403, "Missing " + HEADER_SESSION_ID + " header");
        }
        String actor = req.getActor();
        List<String> roles = new ArrayList<>();
        if (webUi.isPresent()) {
            try {
                webUi.get().getUserContext().getWebAuthUser().ifPresent(u -> roles.addAll(u.getRoles()));
            } catch (RuntimeException e) {
                log.debug("Unable to read web UI roles", e);
            }
        }
        return new CallerIdentity(actor, roles, http.getRemoteAddr(), http.getHeader("User-Agent"),
            CallerIdentity.TYPE_API, "api:" + actor);
    }

    private static CallerIdentity fromPerspective(HttpServletRequest http, String sessionId, String project)
        throws ApiException {
        HttpSession httpSession = http.getSession(false);
        Object attr = httpSession == null ? null : httpSession.getAttribute(PerspectiveSessionCollection.SESSION_KEY);
        if (!(attr instanceof PerspectiveSessionCollection collection)) {
            throw new ApiException(403, "No Perspective session is bound to this browser session");
        }
        // projectSessions is keyed by project name; fall back to the id in case a future version keys by id.
        Optional<InternalSession> found = Optional.empty();
        if (project != null && !project.isBlank()) {
            found = collection.findProjectSession(project);
        }
        if (found.isEmpty()) {
            found = collection.findProjectSession(sessionId);
        }
        InternalSession session = found
            .filter(s -> sessionId.equals(String.valueOf(s.getSessionId())))
            .orElseThrow(() -> new ApiException(403, "Perspective session does not belong to this caller"));

        Optional<WebAuthUser> user = session.getWebAuthStatus().getUser();
        String username = user.map(WebAuthUser::getUserName).orElse(null);
        List<String> roles = user.map(u -> (List<String>) new ArrayList<>(u.getRoles())).orElse(List.of());

        String host = null;
        String userAgent = null;
        String deviceType = null;
        try {
            PropertyTree props = session.getPropertyTreeOf(PropertyType.props);
            host = readString(props, "host");
            userAgent = readString(props, "device.userAgent");
            deviceType = readString(props, "device.type");
        } catch (RuntimeException e) {
            log.debug("Unable to read session props; falling back to request headers", e);
        }
        if (host == null || host.isBlank()) {
            host = collection.getRemoteAddr();
        }
        if (userAgent == null || userAgent.isBlank()) {
            userAgent = http.getHeader("User-Agent");
        }
        return new CallerIdentity(username, roles, host, userAgent,
            CallerIdentity.sessionTypeForDevice(deviceType), "perspective:" + sessionId);
    }

    private static String readString(PropertyTree tree, String path) {
        return tree.read(path)
            .map(QualifiedValue::getValue)
            .map(String::valueOf)
            .orElse(null);
    }
}
