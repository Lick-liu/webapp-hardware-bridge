package tigerworkshop.webapphardwarebridge;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HelperSecurityContractTest {
    @Test
    public void serverDoesNotTrustEveryBrowserOrigin() throws Exception {
        String server = read("src/main/java/tigerworkshop/webapphardwarebridge/Server.java");

        assertFalse("Wildcard CORS leaves loopback mutation reachable from arbitrary pages",
                server.contains("CorsRule::anyHost"));
        assertTrue("HTTP handlers must enforce the trusted-origin policy",
                server.contains("trustedOriginPolicy.isHttpRequestAllowed"));
        assertTrue("WebSocket upgrades must enforce the trusted-origin policy",
                server.contains("trustedOriginPolicy.isWebSocketRequestAllowed"));
    }

    @Test
    public void activationRequiresCredentialAndHasExplicitCorrectionRoute() throws Exception {
        String server = read("src/main/java/tigerworkshop/webapphardwarebridge/Server.java");
        String request = read("src/main/java/tigerworkshop/webapphardwarebridge/dtos/LocalPrintDeviceActivationDTO.java");

        assertTrue("Activation requests need an opaque helper-issued credential",
                request.contains("activationToken"));
        assertTrue("Ordinary activation must pass the credential to the service",
                server.contains("request.getActivationToken()"));
        assertTrue("Cursor repair must be an explicit authenticated operation",
                server.contains("javalinServer.put(\"/system/local-print-device-context/activation.json\""));
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath), StandardCharsets.UTF_8);
    }
}
