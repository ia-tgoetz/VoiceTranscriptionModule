package com.wargoetz.voicetranscription.gateway.web;

import java.util.List;

/**
 * Who is recording, as established by the gateway (never trusted from the request body).
 *
 * @param ownerKey binds a recording to the caller that started it so another session cannot append to or
 *                 submit it, even if it learns the recording id.
 */
public record CallerIdentity(
    String username,
    List<String> roles,
    String clientAddress,
    String userAgent,
    String sessionType,
    String ownerKey
) {
    public static final String TYPE_WEB = "Perspective Web";
    public static final String TYPE_WORKSTATION = "Perspective Workstation";
    public static final String TYPE_MOBILE = "Perspective Mobile";
    public static final String TYPE_DESIGNER = "Perspective Designer";
    public static final String TYPE_API = "Gateway API";

    public CallerIdentity {
        username = username == null || username.isBlank() ? "anonymous" : username;
        roles = roles == null ? List.of() : List.copyOf(roles);
        clientAddress = clientAddress == null ? "" : clientAddress;
        userAgent = userAgent == null ? "" : userAgent;
    }

    /** Maps Perspective's {@code session.props.device.type} to the sessionType stored in MongoDB. */
    public static String sessionTypeForDevice(String deviceType) {
        if (deviceType == null) {
            return TYPE_WEB;
        }
        return switch (deviceType) {
            case "workstation" -> TYPE_WORKSTATION;
            case "ios", "android" -> TYPE_MOBILE;
            case "designer" -> TYPE_DESIGNER;
            default -> TYPE_WEB;
        };
    }
}
