package cn.teahouses.printhelper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import tigerworkshop.webapphardwarebridge.Constants;

/** Runs only against the launcher's isolated smoke server, never a user's helper. */
final class MacProtocolProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORIGIN = "https://shop.teahouses.cn";
    private static final String CONTEXT = "system/local-print-device-context.json";
    private static final String ACTIVATION = "system/local-print-device-context/activation.json";
    private final URI base;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    MacProtocolProbe(URI base) { this.base = base; }

    String verify(String expectedDeviceId) throws Exception {
        JsonNode version = json("GET", "system/version.json", null, 200);
        require(Constants.VERSION.equals(version.path("version").asText()), "Runtime version mismatch");
        require(json("GET", "system/printer-mappings.json", null, 200).isArray(), "Mappings contract");
        for (String origin : new String[]{ORIGIN, "https://dev.teahouses.cn", "https://test.teahouses.cn"}) {
            HttpResponse<String> response = send("GET", CONTEXT + "?shopId=0", null, origin);
            require(response.statusCode() == 200, "Trusted origin context GET");
            require(origin.equals(response.headers().firstValue("access-control-allow-origin").orElse("")), "CORS origin");
        }
        require(send("GET", CONTEXT + "?shopId=0", null, "https://untrusted.invalid").statusCode() == 403,
                "Untrusted origin must be rejected");
        HttpResponse<String> preflight = client.send(HttpRequest.newBuilder(base.resolve(ACTIVATION))
                .timeout(Duration.ofSeconds(10)).header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        require(preflight.statusCode() >= 200 && preflight.statusCode() < 300
                && preflight.headers().firstValue("access-control-allow-methods").orElse("").contains("POST"), "Activation preflight");
        json("GET", CONTEXT + "?shopId=invalid", null, 400);
        JsonNode context = json("GET", CONTEXT + "?shopId=0", null, 200);
        String deviceId = context.path("deviceId").asText();
        require(deviceId.matches("[a-zA-Z0-9._:-]{1,128}") && "0".equals(context.path("shopId").asText()), "Web identity schema");
        require(context.path("activationToken").asText().matches("[a-zA-Z0-9_-]{20,256}"), "Activation credential");
        if (expectedDeviceId != null) {
            require(expectedDeviceId.equals(deviceId) && context.path("activationTaskId").asLong(-1) == 101,
                    "Identity and activation cursor must survive process restart");
        } else {
            require(context.path("activationTaskId").isNull(), "Fresh activation cursor");
            json("POST", ACTIVATION, activation(100, "invalid-token"), 403);
            String token = context.path("activationToken").asText();
            JsonNode active = json("POST", ACTIVATION, activation(100, token), 200);
            require(active.path("activationTaskId").asLong() == 100 && deviceId.equals(active.path("deviceId").asText()), "First activation");
            json("POST", ACTIVATION, activation(999, token), 403);
            active = json("POST", ACTIVATION, activation(999, active.path("activationToken").asText()), 200);
            require(active.path("activationTaskId").asLong() == 100, "Activation must not silently advance cursor");
            active = json("PUT", ACTIVATION, activation(101, active.path("activationToken").asText()), 200);
            require(active.path("activationTaskId").asLong() == 101, "Explicit activation correction");
        }
        JsonNode otherShop = json("GET", CONTEXT + "?shopId=1", null, 200);
        require(deviceId.equals(otherShop.path("deviceId").asText()) && otherShop.path("activationTaskId").isNull(), "Shop cursor isolation");
        verifyPrintResponse();
        System.out.println("MAC_WEB_PROTOCOL_PASS version=" + Constants.VERSION + " restart=" + (expectedDeviceId != null));
        return deviceId;
    }

    private Map<String, Object> activation(long cursor, String token) {
        return Map.of("shopId", "0", "activationTaskId", cursor, "activationToken", token);
    }

    private JsonNode json(String method, String path, Object body, int status) throws Exception {
        HttpResponse<String> response = send(method, path, body, ORIGIN);
        require(response.statusCode() == status, method + " " + path + ": expected " + status + ", got " + response.statusCode());
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, Object body, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15)).header("Origin", origin);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void verifyPrintResponse() throws Exception {
        // Fresh smoke config has no mappings and fallbackToDefault=false. Never print real paper.
        JsonNode printer = json("GET", "config.json", null, 200).path("printer");
        require(printer.path("mappings").isEmpty() && !printer.path("fallbackToDefault").asBoolean(), "Smoke printer isolation");
        CompletableFuture<String> reply = new CompletableFuture<>();
        WebSocket socket = client.newWebSocketBuilder().header("Origin", ORIGIN).connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + base.getPort() + "/printer"), new WebSocket.Listener() {
                    private final StringBuilder text = new StringBuilder();
                    public void onOpen(WebSocket ws) { ws.request(1); }
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        text.append(data);
                        if (last) reply.complete(text.toString());
                        ws.request(1);
                        return null;
                    }
                    public void onError(WebSocket ws, Throwable error) { reply.completeExceptionally(error); }
                }).get(10, TimeUnit.SECONDS);
        try {
            socket.sendText(JSON.writeValueAsString(Map.of("id", "mac-protocol-probe", "type", "__UNMAPPED_SMOKE__",
                    "raw_content", "cHJvYmU=")), true).get(5, TimeUnit.SECONDS);
            JsonNode result = JSON.readTree(reply.get(15, TimeUnit.SECONDS));
            require("mac-protocol-probe".equals(result.path("id").asText())
                    && result.path("success").isBoolean() && !result.path("success").asBoolean()
                    && !result.path("message").asText().isBlank(), "Correlated failed print response");
        } finally {
            socket.abort();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
