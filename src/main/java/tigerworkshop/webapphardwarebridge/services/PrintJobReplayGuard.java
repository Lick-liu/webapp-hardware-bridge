package tigerworkshop.webapphardwarebridge.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.log4j.Log4j2;
import tigerworkshop.webapphardwarebridge.responses.PrintResult;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Log4j2
public final class PrintJobReplayGuard {
    public static final Duration DEFAULT_SUCCESS_TTL = Duration.ofHours(24);
    public static final int DEFAULT_MAX_SUCCESS_ENTRIES = 10_000;
    public static final int DEFAULT_MAX_FAILED_ENTRIES = 1_000;
    public static final int DEFAULT_MAX_IN_FLIGHT_ENTRIES = 128;
    public static final int DEFAULT_MAX_RECONCILE_ENTRIES = 1_000;
    public static final int MAX_STABLE_JOB_ID_LENGTH = 128;
    public static final String DEFAULT_CACHE_FILENAME = "print-job-success-cache.json";

    private static final String RECONCILE_REQUIRED = "RECONCILE_REQUIRED";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Path cachePath;
    private final Duration successTtl;
    private final int maxSuccessEntries;
    private final int maxFailedEntries;
    private final int maxInFlightEntries;
    private final int maxReconcileEntries;
    private final Clock clock;
    private final Object persistenceLock = new Object();
    private final AtomicInteger inFlightCount = new AtomicInteger();
    private final AtomicInteger successReservationCount = new AtomicInteger();
    private final AtomicInteger reconcileReservationCount = new AtomicInteger();
    private volatile boolean persistenceHealthy = true;

    public enum Action {
        BYPASS,
        EXECUTE,
        IN_PROGRESS,
        REJECT_CAPACITY,
        REJECT_INVALID_ID,
        REJECT_PERSISTENCE,
        REPLAY_SUCCESS,
        REPLAY_RECONCILE
    }

    public enum State {
        PRINTING,
        SUCCESS,
        FAILED,
        RECONCILE_REQUIRED
    }

    public record Decision(Action action, PrintResult cachedResult) {
        private static Decision of(Action action) {
            return new Decision(action, null);
        }
    }

    private record Entry(State state, PrintResult result, long updatedAt) {
        private static Entry printing(long now) {
            return new Entry(State.PRINTING, null, now);
        }

        private static Entry failed(long now) {
            return new Entry(State.FAILED, null, now);
        }

        private static Entry success(PrintResult result, long now) {
            return new Entry(State.SUCCESS, result, now);
        }

        private static Entry reconcile(PrintResult result, long now) {
            return new Entry(State.RECONCILE_REQUIRED, result, now);
        }
    }

    public PrintJobReplayGuard(Path cachePath, Duration successTtl, int maxSuccessEntries, Clock clock) {
        this(
                cachePath,
                successTtl,
                maxSuccessEntries,
                DEFAULT_MAX_FAILED_ENTRIES,
                DEFAULT_MAX_IN_FLIGHT_ENTRIES,
                DEFAULT_MAX_RECONCILE_ENTRIES,
                clock
        );
    }

    public PrintJobReplayGuard(
            Path cachePath,
            Duration successTtl,
            int maxSuccessEntries,
            int maxFailedEntries,
            int maxInFlightEntries,
            Clock clock
    ) {
        this(
                cachePath,
                successTtl,
                maxSuccessEntries,
                maxFailedEntries,
                maxInFlightEntries,
                DEFAULT_MAX_RECONCILE_ENTRIES,
                clock
        );
    }

    public PrintJobReplayGuard(
            Path cachePath,
            Duration successTtl,
            int maxSuccessEntries,
            int maxFailedEntries,
            int maxInFlightEntries,
            int maxReconcileEntries,
            Clock clock
    ) {
        if (cachePath == null) {
            throw new IllegalArgumentException("cachePath is required");
        }
        if (successTtl == null || successTtl.isZero() || successTtl.isNegative()) {
            throw new IllegalArgumentException("successTtl must be positive");
        }
        if (maxSuccessEntries <= 0 || maxFailedEntries <= 0
                || maxInFlightEntries <= 0 || maxReconcileEntries <= 0) {
            throw new IllegalArgumentException("cache limits must be positive");
        }
        this.cachePath = cachePath;
        this.successTtl = successTtl;
        this.maxSuccessEntries = maxSuccessEntries;
        this.maxFailedEntries = maxFailedEntries;
        this.maxInFlightEntries = maxInFlightEntries;
        this.maxReconcileEntries = maxReconcileEntries;
        this.clock = clock;
        loadDurableStates();
    }

    public static PrintJobReplayGuard getInstance() {
        return Holder.INSTANCE;
    }

    public Decision begin(String rawId) {
        if (rawId != null && !rawId.isBlank() && rawId.trim().length() > MAX_STABLE_JOB_ID_LENGTH) {
            return Decision.of(Action.REJECT_INVALID_ID);
        }
        String id = normalizeId(rawId);
        if (id == null) {
            return Decision.of(Action.BYPASS);
        }

        long now = clock.millis();
        pruneExpiredEntries(now);
        AtomicReference<Decision> decision = new AtomicReference<>();
        entries.compute(id, (ignored, current) -> {
            if (current != null && current.state == State.PRINTING) {
                decision.set(Decision.of(Action.IN_PROGRESS));
                return current;
            }
            if (current != null && current.state == State.RECONCILE_REQUIRED) {
                decision.set(new Decision(Action.REPLAY_RECONCILE, copyResult(current.result, rawId)));
                return current;
            }
            if (current != null && current.state == State.SUCCESS && !isExpired(current, now)) {
                decision.set(new Decision(Action.REPLAY_SUCCESS, copyResult(current.result, rawId)));
                return current;
            }
            if (!persistenceHealthy) {
                decision.set(Decision.of(Action.REJECT_PERSISTENCE));
                return current;
            }
            if (!tryAcquireInFlight()) {
                decision.set(Decision.of(Action.REJECT_CAPACITY));
                return current;
            }
            if (!tryAcquireReconcileReservation()) {
                releaseInFlight(true);
                decision.set(Decision.of(Action.REJECT_CAPACITY));
                return current;
            }
            if (!tryAcquireSuccessReservation()) {
                releaseInFlight(true);
                releaseReconcileReservation(true);
                decision.set(Decision.of(Action.REJECT_CAPACITY));
                return current;
            }
            decision.set(Decision.of(Action.EXECUTE));
            return Entry.printing(now);
        });

        if (decision.get().action() == Action.EXECUTE && !persistDurableStates(now)) {
            AtomicBoolean rolledBack = new AtomicBoolean();
            entries.compute(id, (ignored, current) -> {
                if (current != null && current.state == State.PRINTING) {
                    rolledBack.set(true);
                    return Entry.failed(now);
                }
                return current;
            });
            if (rolledBack.get()) {
                releaseInFlight(true);
                releaseSuccessReservation(true);
                releaseReconcileReservation(true);
            }
            return Decision.of(Action.REJECT_PERSISTENCE);
        }
        return decision.get();
    }

    public void succeed(String rawId, PrintResult result) {
        String id = normalizeId(rawId);
        if (id == null) {
            return;
        }
        long now = clock.millis();
        PrintResult cached = copyResult(result, id);
        AtomicBoolean completedInFlight = new AtomicBoolean();
        entries.compute(id, (ignored, current) -> {
            completedInFlight.set(current != null && current.state == State.PRINTING);
            return Entry.success(cached, now);
        });
        releaseInFlight(completedInFlight.get());
        releaseReconcileReservation(completedInFlight.get());
        persistDurableStates(now);
    }

    public void fail(String rawId) {
        String id = normalizeId(rawId);
        if (id == null) {
            return;
        }
        long now = clock.millis();
        AtomicBoolean completedInFlight = new AtomicBoolean();
        entries.compute(id, (ignored, current) -> {
            completedInFlight.set(current != null && current.state == State.PRINTING);
            return Entry.failed(now);
        });
        releaseInFlight(completedInFlight.get());
        releaseSuccessReservation(completedInFlight.get());
        releaseReconcileReservation(completedInFlight.get());
        pruneExpiredEntries(now);
        trimOldestFailures();
        persistDurableStates(now);
    }

    private boolean isExpired(Entry entry, long now) {
        return (entry.state == State.SUCCESS || entry.state == State.FAILED)
                && now - entry.updatedAt >= successTtl.toMillis();
    }

    private String normalizeId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        return rawId.trim();
    }

    private boolean tryAcquireInFlight() {
        return tryIncrementBelowLimit(inFlightCount, maxInFlightEntries);
    }

    private boolean tryAcquireSuccessReservation() {
        return tryIncrementBelowLimit(successReservationCount, maxSuccessEntries);
    }

    private boolean tryAcquireReconcileReservation() {
        return tryIncrementBelowLimit(reconcileReservationCount, maxReconcileEntries);
    }

    private boolean tryIncrementBelowLimit(AtomicInteger counter, int limit) {
        while (true) {
            int current = counter.get();
            if (current >= limit) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void releaseInFlight(boolean release) {
        if (release) {
            inFlightCount.decrementAndGet();
        }
    }

    private void releaseSuccessReservation(boolean release) {
        if (release) {
            successReservationCount.decrementAndGet();
        }
    }

    private void releaseReconcileReservation(boolean release) {
        if (release) {
            reconcileReservationCount.decrementAndGet();
        }
    }

    private PrintResult copyResult(PrintResult source, String id) {
        if (source == null) {
            return new PrintResult(true, "Success", id, null, "SUCCESS");
        }
        return new PrintResult(
                Boolean.TRUE.equals(source.success),
                source.message,
                id,
                source.printerName,
                source.state
        );
    }

    private PrintResult reconcileResult(String id) {
        return new PrintResult(
                true,
                "本地打印助手曾在打印过程中退出，物理提交结果需要人工核对",
                id,
                null,
                RECONCILE_REQUIRED
        );
    }

    private void loadDurableStates() {
        if (!Files.isRegularFile(cachePath)) {
            return;
        }
        long now = clock.millis();
        boolean convertedPrinting = false;
        try {
            JsonNode root = OBJECT_MAPPER.readTree(cachePath.toFile());
            if (root == null || !root.isArray()) {
                throw new IOException("cache root must be an array");
            }
            for (JsonNode node : root) {
                String id = normalizeId(node.path("id").asText(null));
                if (id == null) {
                    continue;
                }
                String stateValue = node.path("state").asText(null);
                if (stateValue == null && node.path("completedAt").asLong(0) > 0) {
                    stateValue = State.SUCCESS.name();
                }
                if (State.SUCCESS.name().equals(stateValue)) {
                    long completedAt = readTimestamp(node, "completedAt", "updatedAt");
                    if (completedAt <= 0 || now - completedAt >= successTtl.toMillis()) {
                        continue;
                    }
                    PrintResult result = new PrintResult(
                            true,
                            node.path("message").asText("Success"),
                            id,
                            node.path("printerName").isNull() ? null : node.path("printerName").asText(null),
                            State.SUCCESS.name()
                    );
                    entries.put(id, Entry.success(result, completedAt));
                    successReservationCount.incrementAndGet();
                } else if (State.PRINTING.name().equals(stateValue)
                        || State.RECONCILE_REQUIRED.name().equals(stateValue)) {
                    long updatedAt = readTimestamp(node, "updatedAt", "completedAt");
                    entries.put(id, Entry.reconcile(reconcileResult(id), updatedAt > 0 ? updatedAt : now));
                    reconcileReservationCount.incrementAndGet();
                    convertedPrinting |= State.PRINTING.name().equals(stateValue);
                } else {
                    throw new IOException("unsupported cache entry state: " + stateValue);
                }
            }
            if (successReservationCount.get() > maxSuccessEntries
                    || reconcileReservationCount.get() > maxReconcileEntries) {
                persistenceHealthy = false;
                log.error("Print replay cache exceeds safe capacity; refusing new stable-id print jobs");
            }
            log.info("Loaded {} successful and {} reconciliation-required print job ids from {}",
                    successReservationCount.get(), reconcileReservationCount.get(), cachePath);
            if (convertedPrinting && persistenceHealthy) {
                persistDurableStates(now);
            }
        } catch (Exception exception) {
            persistenceHealthy = false;
            entries.clear();
            successReservationCount.set(0);
            reconcileReservationCount.set(0);
            log.error("Unable to load print replay state {}; refusing new stable-id print jobs",
                    cachePath, exception);
        }
    }

    private long readTimestamp(JsonNode node, String preferred, String fallback) {
        long value = node.path(preferred).asLong(0);
        return value > 0 ? value : node.path(fallback).asLong(0);
    }

    private boolean persistDurableStates(long now) {
        synchronized (persistenceLock) {
            if (!persistenceHealthy) {
                return false;
            }
            pruneExpiredEntries(now);
            ArrayNode root = OBJECT_MAPPER.createArrayNode();
            durableEntriesOldestFirst().forEach(item -> {
                Entry entry = item.getValue();
                ObjectNode node = root.addObject();
                node.put("id", item.getKey());
                node.put("state", entry.state.name());
                node.put("updatedAt", entry.updatedAt);
                if (entry.state == State.SUCCESS) {
                    node.put("completedAt", entry.updatedAt);
                    node.put("message", entry.result.message);
                    if (entry.result.printerName == null) {
                        node.putNull("printerName");
                    } else {
                        node.put("printerName", entry.result.printerName);
                    }
                }
            });

            Path parent = cachePath.toAbsolutePath().getParent();
            Path temporaryPath = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
            try {
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                        temporaryPath,
                        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
                try (FileChannel channel = FileChannel.open(temporaryPath, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                moveAtomically(temporaryPath, cachePath);
                return true;
            } catch (Exception exception) {
                persistenceHealthy = false;
                log.error("Unable to persist print replay state to {}; refusing new stable-id print jobs",
                        cachePath, exception);
                return false;
            }
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void pruneExpiredEntries(long now) {
        for (java.util.Map.Entry<String, Entry> item : entries.entrySet()) {
            Entry entry = item.getValue();
            if (!isExpired(entry, now) || !entries.remove(item.getKey(), entry)) {
                continue;
            }
            releaseSuccessReservation(entry.state == State.SUCCESS);
        }
    }

    private void trimOldestFailures() {
        List<java.util.Map.Entry<String, Entry>> failures = entries.entrySet().stream()
                .filter(item -> item.getValue().state == State.FAILED)
                .sorted(Comparator.comparingLong(item -> item.getValue().updatedAt))
                .toList();
        int overflow = failures.size() - maxFailedEntries;
        for (int index = 0; index < overflow; index++) {
            java.util.Map.Entry<String, Entry> item = failures.get(index);
            entries.remove(item.getKey(), item.getValue());
        }
    }

    private List<java.util.Map.Entry<String, Entry>> durableEntriesOldestFirst() {
        return entries.entrySet().stream()
                .filter(item -> item.getValue().state == State.SUCCESS
                        || item.getValue().state == State.PRINTING
                        || item.getValue().state == State.RECONCILE_REQUIRED)
                .sorted(Comparator.comparingLong(item -> item.getValue().updatedAt))
                .toList();
    }

    int failedEntryCount() {
        return (int) entries.values().stream().filter(entry -> entry.state == State.FAILED).count();
    }

    int inFlightEntryCount() {
        return inFlightCount.get();
    }

    private static final class Holder {
        private static final PrintJobReplayGuard INSTANCE = new PrintJobReplayGuard(
                Path.of(DEFAULT_CACHE_FILENAME),
                DEFAULT_SUCCESS_TTL,
                DEFAULT_MAX_SUCCESS_ENTRIES,
                Clock.systemUTC()
        );
    }
}
