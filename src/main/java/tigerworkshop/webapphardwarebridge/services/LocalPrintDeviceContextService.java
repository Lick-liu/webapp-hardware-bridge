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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class LocalPrintDeviceContextService {
    public static final String DEFAULT_FILENAME = "local-print-device-context.json";
    private static final String DEVICE_ID_PREFIX = "local-print-device-";
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern SHOP_ID_PATTERN = Pattern.compile("[0-9]{1,32}");

    private final Path contextFile;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private LocalPrintDeviceState state;

    public LocalPrintDeviceContextService(Path contextFile) {
        this.contextFile = contextFile.toAbsolutePath();
    }

    public synchronized LocalPrintDeviceContextDTO getContext(String shopId) throws IOException {
        String normalizedShopId = normalizeShopId(shopId);
        LocalPrintDeviceState current = loadOrCreate();
        return toContext(current, normalizedShopId);
    }

    public synchronized LocalPrintDeviceContextDTO activate(
            String shopId, Long activationTaskId) throws IOException {
        String normalizedShopId = normalizeShopId(shopId);
        if (activationTaskId == null || activationTaskId < 0) {
            throw new IllegalArgumentException("activationTaskId must be a non-negative integer");
        }

        LocalPrintDeviceState current = loadOrCreate();
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
        return new LocalPrintDeviceContextDTO(
                current.getDeviceId(),
                shopId,
                current.getShopActivationTaskIds().get(shopId));
    }

    private String normalizeShopId(String shopId) {
        if (shopId == null || !SHOP_ID_PATTERN.matcher(shopId.trim()).matches()) {
            throw new IllegalArgumentException("shopId must contain digits only");
        }
        return shopId.trim();
    }
}
