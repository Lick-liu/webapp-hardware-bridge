package tigerworkshop.webapphardwarebridge;

import io.javalin.http.HandlerType;
import io.javalin.plugin.bundled.CorsPluginConfig;
import tigerworkshop.webapphardwarebridge.dtos.Config;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class TrustedOriginPolicy {
    private final Set<String> allowedOrigins;

    private TrustedOriginPolicy(Set<String> allowedOrigins) {
        this.allowedOrigins = Set.copyOf(allowedOrigins);
    }

    public static TrustedOriginPolicy from(Config.Server serverConfig) {
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        if (serverConfig.getAllowedOrigins() != null) {
            for (String origin : serverConfig.getAllowedOrigins()) {
                origins.add(normalizeConfiguredOrigin(origin));
            }
        }
        origins.add(normalizeConfiguredOrigin(serverConfig.getUri()));
        if (origins.isEmpty()) {
            throw new IllegalArgumentException("At least one trusted browser origin is required");
        }
        return new TrustedOriginPolicy(origins);
    }

    public void configureCors(CorsPluginConfig.CorsRule rule) {
        for (String origin : allowedOrigins) {
            rule.allowHost(origin);
        }
    }

    public boolean isTrustedOrigin(String origin) {
        if (origin == null || origin.isBlank() || "null".equalsIgnoreCase(origin.trim())) {
            return false;
        }
        try {
            return allowedOrigins.contains(normalizeOrigin(origin));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public boolean isHttpRequestAllowed(
            String origin, HandlerType method, boolean authenticated) {
        if (origin != null && !origin.isBlank()) {
            return isTrustedOrigin(origin);
        }
        return isSafeRead(method) || authenticated;
    }

    public boolean isWebSocketRequestAllowed(String origin, boolean authenticated) {
        if (origin != null && !origin.isBlank()) {
            return isTrustedOrigin(origin);
        }
        return authenticated;
    }

    Set<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    private static boolean isSafeRead(HandlerType method) {
        return method == HandlerType.GET
                || method == HandlerType.HEAD
                || method == HandlerType.OPTIONS;
    }

    private static String normalizeConfiguredOrigin(String origin) {
        try {
            return normalizeOrigin(origin);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid trusted browser origin: " + origin, exception);
        }
    }

    private static String normalizeOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            throw new IllegalArgumentException("Origin cannot be blank");
        }
        URI uri = URI.create(origin.trim());
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null
                || host == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getRawUserInfo() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Origin must contain only scheme, host, and optional port");
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = (port == 80 && normalizedScheme.equals("http"))
                || (port == 443 && normalizedScheme.equals("https"));
        return normalizedScheme + "://" + normalizedHost
                + (port < 0 || defaultPort ? "" : ":" + port);
    }
}
