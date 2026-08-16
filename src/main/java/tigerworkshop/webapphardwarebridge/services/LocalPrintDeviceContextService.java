package tigerworkshop.webapphardwarebridge.services;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import tigerworkshop.webapphardwarebridge.dtos.LocalPrintDeviceContextDTO;
import tigerworkshop.webapphardwarebridge.dtos.LocalPrintDeviceState;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class LocalPrintDeviceContextService {
    public static final String DEFAULT_FILENAME = "local-print-device-context.json";
    private static final String DEVICE_ID_PREFIX = "local-print-device-";
    private static final Duration ACTIVATION_TOKEN_TTL = Duration.ofMinutes(5);
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern SHOP_ID_PATTERN = Pattern.compile("[0-9]{1,32}");

    private final Path contextFile;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, ActivationCredential> activationCredentials = new HashMap<>();
    private LocalPrintDeviceState state;

    public LocalPrintDeviceContextService(Path contextFile) {
        this(contextFile, Clock.systemUTC());
    }

    LocalPrintDeviceContextService(Path contextFile, Clock clock) {
        this.contextFile = contextFile.toAbsolutePath();
        this.clock = clock;
    }

    public synchronized LocalPrintDeviceContextDTO getContext(String shopId) throws IOException {
        String normalizedShopId = normalizeShopId(shopId);
        LocalPrintDeviceState current = loadOrCreate();
        return toContext(current, normalizedShopId);
    }

    public synchronized LocalPrintDeviceContextDTO activate(
            String shopId, Long activationTaskId, String activationToken) throws IOException {
        String normalizedShopId = normalizeShopId(shopId);
        validateActivationTaskId(activationTaskId);

        LocalPrintDeviceState current = loadOrCreate();
        consumeActivationCredential(
                normalizedShopId, current.getDeviceId(), activationToken);
        Long existing = current.getShopActivationTaskIds().get(normalizedShopId);
        if (existing != null) {
            return toContext(current, normalizedShopId);
        }

        Map<String, Long> updatedCursors = new LinkedHashMap<>(current.getShopActivationTaskIds());
        updatedCursors.put(normalizedShopId, activationTaskId);
        LocalPrintDeviceState updated =
                new LocalPrintDeviceState(current.getDeviceId(), updatedCursors);
        persistAtomically(updated);
        state = updated;
        return toContext(updated, normalizedShopId);
    }

    public synchronized LocalPrintDeviceContextDTO correctActivation(
            String shopId, Long activationTaskId, String activationToken) throws IOException {
        String normalizedShopId = normalizeShopId(shopId);
        validateActivationTaskId(activationTaskId);

        LocalPrintDeviceState current = loadOrCreate();
        consumeActivationCredential(
                normalizedShopId, current.getDeviceId(), activationToken);
        Map<String, Long> updatedCursors = new LinkedHashMap<>(current.getShopActivationTaskIds());
        updatedCursors.put(normalizedShopId, activationTaskId);
        LocalPrintDeviceState updated =
                new LocalPrintDeviceState(current.getDeviceId(), updatedCursors);
        persistAtomically(updated);
        state = updated;
        return toContext(updated, normalizedShopId);
    }

    private LocalPrintDeviceState loadOrCreate() throws IOException {
        if (state != null) {
            return state;
        }
        if (Files.exists(contextFile)) {
            if (!Files.isRegularFile(contextFile)) {
                throw new IOException("Local print device context is not a regular file");
            }
            LocalPrintDeviceState loaded = objectMapper.readValue(
                    contextFile.toFile(), LocalPrintDeviceState.class);
            validateState(loaded);
            state = loaded;
            return loaded;
        }

        LocalPrintDeviceState created = new LocalPrintDeviceState(
                DEVICE_ID_PREFIX + UUID.randomUUID(), new LinkedHashMap<>());
        persistAtomically(created);
        state = created;
        return created;
    }

    private void validateState(LocalPrintDeviceState candidate) throws IOException {
        if (candidate == null
                || candidate.getDeviceId() == null
                || !DEVICE_ID_PATTERN.matcher(candidate.getDeviceId()).matches()
                || candidate.getShopActivationTaskIds() == null) {
            throw new IOException("Invalid local print device context");
        }
        for (Map.Entry<String, Long> entry : candidate.getShopActivationTaskIds().entrySet()) {
            if (!SHOP_ID_PATTERN.matcher(entry.getKey()).matches()
                    || entry.getValue() == null
                    || entry.getValue() < 0) {
                throw new IOException("Invalid local print device activation cursor");
            }
        }
    }

    private void persistAtomically(LocalPrintDeviceState candidate) throws IOException {
        Path parent = contextFile.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("Local print device context directory is unavailable");
        }
        Path temporary = Files.createTempFile(
                parent, contextFile.getFileName().toString() + ".", ".tmp");
        try {
            byte[] json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(candidate);
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(json);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, contextFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private LocalPrintDeviceContextDTO toContext(
            LocalPrintDeviceState current, String shopId) {
        String activationToken = issueActivationCredential(shopId, current.getDeviceId());
        return new LocalPrintDeviceContextDTO(
                current.getDeviceId(),
                shopId,
                current.getShopActivationTaskIds().get(shopId),
                activationToken);
    }

    private String issueActivationCredential(String shopId, String deviceId) {
        Instant now = clock.instant();
        ActivationCredential existing = activationCredentials.get(shopId);
        if (existing != null
                && existing.expiresAt().isAfter(now)
                && existing.deviceId().equals(deviceId)) {
            return existing.token();
        }
        ActivationCredential created = new ActivationCredential(
                UUID.randomUUID().toString(),
                deviceId,
                now.plus(ACTIVATION_TOKEN_TTL));
        activationCredentials.put(shopId, created);
        return created.token();
    }

    private void consumeActivationCredential(
            String shopId, String deviceId, String activationToken) {
        ActivationCredential expected = activationCredentials.get(shopId);
        Instant now = clock.instant();
        boolean valid = expected != null
                && expected.expiresAt().isAfter(now)
                && expected.deviceId().equals(deviceId)
                && activationToken != null
                && MessageDigest.isEqual(
                        expected.token().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        activationToken.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!valid) {
            if (expected != null && !expected.expiresAt().isAfter(now)) {
                activationCredentials.remove(shopId);
            }
            throw new InvalidActivationTokenException();
        }
        activationCredentials.remove(shopId);
    }

    private void validateActivationTaskId(Long activationTaskId) {
        if (activationTaskId == null || activationTaskId < 0) {
            throw new IllegalArgumentException("activationTaskId must be a non-negative integer");
        }
    }

    private String normalizeShopId(String shopId) {
        if (shopId == null || !SHOP_ID_PATTERN.matcher(shopId.trim()).matches()) {
            throw new IllegalArgumentException("shopId must contain digits only");
        }
        return shopId.trim();
    }

    private record ActivationCredential(String token, String deviceId, Instant expiresAt) {
    }

    public static class InvalidActivationTokenException extends IllegalArgumentException {
        public InvalidActivationTokenException() {
            super("Local print device activation credential is invalid or expired");
        }
    }
}
