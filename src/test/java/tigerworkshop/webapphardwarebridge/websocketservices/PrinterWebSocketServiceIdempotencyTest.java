package tigerworkshop.webapphardwarebridge.websocketservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tigerworkshop.webapphardwarebridge.interfaces.WebSocketServerInterface;
import tigerworkshop.webapphardwarebridge.interfaces.WebSocketServiceInterface;
import tigerworkshop.webapphardwarebridge.responses.PrintDocument;
import tigerworkshop.webapphardwarebridge.responses.PrintResult;
import tigerworkshop.webapphardwarebridge.services.PrintJobReplayGuard;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PrinterWebSocketServiceIdempotencyTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void concurrentSameIdEntersPhysicalExecutorOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        RecordingServer server = new RecordingServer();
        PrinterWebSocketService service = service(server, document -> {
            executions.incrementAndGet();
            entered.countDown();
            release.await();
            return success(document.getId());
        });
        PrintDocument document = document("same-id");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> print(service, document));
            entered.await();
            Future<?> duplicate = executor.submit(() -> print(service, document));
            duplicate.get();
            assertEquals(1, executions.get());

            release.countDown();
            first.get();

            assertEquals(1, executions.get());
            assertEquals(1, server.printResults.size());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void sequentialSuccessReplaysResponseWithoutPhysicalExecution() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        RecordingServer server = new RecordingServer();
        PrinterWebSocketService service = service(server, document -> {
            executions.incrementAndGet();
            return success(document.getId());
        });
        PrintDocument document = document("same-id");

        service.printDocument(document);
        service.printDocument(document);

        assertEquals(1, executions.get());
        assertEquals(2, server.printResults.size());
        assertTrue(server.printResults.stream().allMatch(result -> result.success));
    }

    @Test
    public void failedIdRetriesAndDifferentIdExecutes() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        RecordingServer server = new RecordingServer();
        PrinterWebSocketService service = service(server, document -> {
            int attempt = executions.incrementAndGet();
            if ("retry-id".equals(document.getId()) && attempt == 1) {
                return new PrintResult(false, "offline", document.getId(), null);
            }
            return success(document.getId());
        });

        service.printDocument(document("retry-id"));
        service.printDocument(document("retry-id"));
        service.printDocument(document("different-id"));

        assertEquals(3, executions.get());
        assertEquals(List.of(false, true, true), server.printResults.stream().map(result -> result.success).toList());
    }

    @Test
    public void restartedServiceReplaysPersistedSuccessWithoutPhysicalExecution() throws Exception {
        Path cache = temporaryFolder.newFile("restart-cache.json").toPath();
        assertTrue(cache.toFile().delete());
        AtomicInteger executions = new AtomicInteger();
        RecordingServer firstServer = new RecordingServer();
        PrintJobReplayGuard firstGuard = new PrintJobReplayGuard(
                cache, Duration.ofHours(24), 100, Clock.systemUTC());
        PrinterWebSocketService firstService = new PrinterWebSocketService(firstGuard, document -> {
            executions.incrementAndGet();
            return success(document.getId());
        });
        firstService.onRegister(firstServer);
        firstService.printDocument(document("persisted-id"));

        RecordingServer restartedServer = new RecordingServer();
        PrintJobReplayGuard restartedGuard = new PrintJobReplayGuard(
                cache, Duration.ofHours(24), 100, Clock.systemUTC());
        PrinterWebSocketService restartedService = new PrinterWebSocketService(restartedGuard, document -> {
            executions.incrementAndGet();
            return success(document.getId());
        });
        restartedService.onRegister(restartedServer);
        restartedService.printDocument(document("persisted-id"));

        assertEquals(1, executions.get());
        assertEquals(1, restartedServer.printResults.size());
        assertTrue(restartedServer.printResults.get(0).success);
    }

    @Test
    public void restartedServiceRequiresReconciliationForAJobThatWasPrintingAtShutdown() throws Exception {
        Path cache = temporaryFolder.newFile("restart-printing-cache.json").toPath();
        assertTrue(cache.toFile().delete());
        PrintJobReplayGuard firstGuard = new PrintJobReplayGuard(
                cache, Duration.ofHours(24), 100, Clock.systemUTC());
        assertEquals(PrintJobReplayGuard.Action.EXECUTE, firstGuard.begin("uncertain-id").action());

        AtomicInteger executions = new AtomicInteger();
        RecordingServer restartedServer = new RecordingServer();
        PrintJobReplayGuard restartedGuard = new PrintJobReplayGuard(
                cache, Duration.ofHours(24), 100, Clock.systemUTC());
        PrinterWebSocketService restartedService = new PrinterWebSocketService(restartedGuard, document -> {
            executions.incrementAndGet();
            return success(document.getId());
        });
        restartedService.onRegister(restartedServer);

        restartedService.printDocument(document("uncertain-id"));

        assertEquals(0, executions.get());
        assertEquals(1, restartedServer.printResults.size());
        assertEquals(true, restartedServer.printResults.get(0).success);
        assertEquals("RECONCILE_REQUIRED", restartedServer.printResults.get(0).state);
    }

    @Test
    public void rejectsNewPhysicalWorkWhenTheInFlightGateIsFull() throws Exception {
        Path cache = temporaryFolder.newFile("capacity-cache.json").toPath();
        assertTrue(cache.toFile().delete());
        PrintJobReplayGuard guard = new PrintJobReplayGuard(
                cache, Duration.ofHours(24), 100, 10, 1, Clock.systemUTC());
        assertEquals(PrintJobReplayGuard.Action.EXECUTE, guard.begin("already-printing").action());
        AtomicInteger executions = new AtomicInteger();
        RecordingServer server = new RecordingServer();
        PrinterWebSocketService service = new PrinterWebSocketService(guard, document -> {
            executions.incrementAndGet();
            return success(document.getId());
        });
        service.onRegister(server);

        service.printDocument(document("capacity-rejected"));

        assertEquals(0, executions.get());
        assertEquals(1, server.printResults.size());
        assertEquals(false, server.printResults.get(0).success);
        assertEquals("capacity-rejected", server.printResults.get(0).id);
    }

    @Test
    public void rejectsOversizedStableIdBeforeCallingThePhysicalExecutor() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        RecordingServer server = new RecordingServer();
        PrinterWebSocketService service = service(server, document -> {
            executions.incrementAndGet();
            return success(document.getId());
        });
        String oversizedId = "x".repeat(PrintJobReplayGuard.MAX_STABLE_JOB_ID_LENGTH + 1);

        service.printDocument(document(oversizedId));

        assertEquals(0, executions.get());
        assertEquals(1, server.printResults.size());
        assertEquals(false, server.printResults.get(0).success);
        assertEquals(oversizedId, server.printResults.get(0).id);
    }

    private PrinterWebSocketService service(
            RecordingServer server,
            PrinterWebSocketService.PrintExecutor executor
    ) throws Exception {
        Path cache = temporaryFolder.newFile().toPath();
        assertTrue(cache.toFile().delete());
        PrintJobReplayGuard guard = new PrintJobReplayGuard(
                cache, Duration.ofHours(24), 100, Clock.systemUTC());
        PrinterWebSocketService service = new PrinterWebSocketService(guard, executor);
        service.onRegister(server);
        return service;
    }

    private PrintDocument document(String id) throws Exception {
        return OBJECT_MAPPER.readValue(
                "{\"id\":\"" + id + "\",\"type\":\"RECEIPT\",\"raw_content\":\"AA==\"}",
                PrintDocument.class);
    }

    private PrintResult success(String id) {
        return new PrintResult(true, "Success", id, "Fake Printer");
    }

    private void print(PrinterWebSocketService service, PrintDocument document) {
        try {
            service.printDocument(document);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private static final class RecordingServer implements WebSocketServerInterface {
        private final List<PrintResult> printResults = new CopyOnWriteArrayList<>();

        @Override
        public void messageToServer(String channel, String message) {
            try {
                printResults.add(OBJECT_MAPPER.readValue(message, PrintResult.class));
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }

        @Override
        public void messageToServer(String channel, byte[] message) {
        }

        @Override
        public void messageToService(String channel, String message) {
        }

        @Override
        public void messageToService(String channel, byte[] message) {
        }

        @Override
        public void registerService(WebSocketServiceInterface service) {
        }

        @Override
        public void unregisterService(WebSocketServiceInterface service) {
        }
    }
}
