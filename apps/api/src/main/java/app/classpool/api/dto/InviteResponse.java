package app.classpool.api.dto;

public record InviteResponse(String token, String joinUrl, String qrPayload, String channel) {
}
