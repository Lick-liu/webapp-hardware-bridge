package tigerworkshop.webapphardwarebridge;

import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import org.junit.Test;
import tigerworkshop.webapphardwarebridge.dtos.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TrustedOriginPolicyTest {
    @Test
    public void exactOriginsDoNotAcceptPrefixSuffixOpaqueOrWildcardValues() {
        TrustedOriginPolicy policy = policy("https://shop.teahouses.cn");

        assertTrue(policy.isTrustedOrigin("https://shop.teahouses.cn"));
        assertTrue(policy.isTrustedOrigin("HTTPS://SHOP.TEAHOUSES.CN:443"));
        assertFalse(policy.isTrustedOrigin("https://shop.teahouses.cn.evil.example"));
        assertFalse(policy.isTrustedOrigin("https://evil.example/?next=https://shop.teahouses.cn"));
        assertFalse(policy.isTrustedOrigin("null"));
        assertFalse(policy.isTrustedOrigin(null));

        Config.Server invalid = new Config.Server();
        invalid.setAllowedOrigins(new ArrayList<>(List.of("*")));
        assertThrows(IllegalArgumentException.class, () -> TrustedOriginPolicy.from(invalid));
    }

    @Test
    public void originlessMutationsAndWebSocketsNeedConfiguredAuthentication() {
        TrustedOriginPolicy policy = policy("https://shop.teahouses.cn");

        assertTrue(policy.isHttpRequestAllowed(null, HandlerType.GET, false));
        assertFalse(policy.isHttpRequestAllowed(null, HandlerType.POST, false));
        assertTrue(policy.isHttpRequestAllowed(null, HandlerType.POST, true));
        assertFalse(policy.isWebSocketRequestAllowed(null, false));
        assertTrue(policy.isWebSocketRequestAllowed(null, true));
        assertFalse(policy.isWebSocketRequestAllowed("https://evil.example", true));
    }

    @Test
    public void realCorsAndBeforeHandlersRejectUntrustedBrowserWrites() throws Exception {
        TrustedOriginPolicy policy = policy("https://shop.teahouses.cn");
        AtomicInteger writes = new AtomicInteger();
        Javalin app = Javalin.create(config -> config.bundledPlugins.enableCors(cors ->
                cors.addRule(policy::configureCors)));
        app.before(context -> {
            if (!policy.isHttpRequestAllowed(
                    context.header("Origin"), context.method(), false)) {
                context.status(403).result("Untrusted browser origin");
                context.skipRemainingHandlers();
            }
        });
        app.post("/activation", context -> {
            writes.incrementAndGet();
            context.status(204);
        });
        app.start("127.0.0.1", 0);
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + app.port() + "/activation");
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> untrusted = client.send(
                    HttpRequest.newBuilder(endpoint)
                            .header("Origin", "https://evil.example")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, untrusted.statusCode());
            assertEquals(0, writes.get());

            HttpResponse<String> originless = client.send(
                    HttpRequest.newBuilder(endpoint)
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, originless.statusCode());
            assertEquals(0, writes.get());

            HttpResponse<String> trusted = client.send(
                    HttpRequest.newBuilder(endpoint)
                            .header("Origin", "https://shop.teahouses.cn")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(204, trusted.statusCode());
            assertEquals(1, writes.get());

            HttpResponse<String> trustedPreflight = client.send(
                    HttpRequest.newBuilder(endpoint)
                            .header("Origin", "https://shop.teahouses.cn")
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "content-type")
                            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(trustedPreflight.statusCode() < 400);
            assertEquals("https://shop.teahouses.cn", trustedPreflight.headers()
                    .firstValue("Access-Control-Allow-Origin").orElse(null));

            HttpResponse<String> untrustedPreflight = client.send(
                    HttpRequest.newBuilder(endpoint)
                            .header("Origin", "https://evil.example")
                            .header("Access-Control-Request-Method", "POST")
                            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(untrustedPreflight.statusCode() >= 400);
            assertTrue(untrustedPreflight.headers()
                    .firstValue("Access-Control-Allow-Origin").isEmpty());
        } finally {
            app.stop();
        }
    }

    private TrustedOriginPolicy policy(String... origins) {
        Config.Server server = new Config.Server();
        server.setAllowedOrigins(new ArrayList<>(List.of(origins)));
        return TrustedOriginPolicy.from(server);
    }
}
