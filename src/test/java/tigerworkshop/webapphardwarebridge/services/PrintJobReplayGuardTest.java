package tigerworkshop.webapphardwarebridge.services;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tigerworkshop.webapphardwarebridge.responses.PrintResult;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PrintJobReplayGuardTest {
    private static final Duration TTL = Duration.ofHours(24);

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void grantsConcurrentSameIdToExactlyOneExecutor() throws Exception {
        PrintJobReplayGuard guard = guard(Clock.systemUTC(), 100);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<PrintJobReplayGuard.Decision>> tasks = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                tasks.add(() -> {
                    ready.countDown();
                    start.await();
                    return guard.begin("same-job");
                });
            }
            List<Future<PrintJobReplayGuard.Decision>> futures = tasks.stream()
                    .map(executor::submit)
                    .toList();
            ready.await();
            start.countDown();

            List<PrintJobReplayGuard.Action> actions = new ArrayList<>();
            for (Future<PrintJobReplayGuard.Decision> future : futures) {
                actions.add(future.get().action());
            }

            assertEquals(1, actions.stream().filter(action -> action == PrintJobReplayGuard.Action.EXECUTE).count());
            assertEquals(7, actions.stream().filter(action -> action == PrintJobReplayGuard.Action.IN_PROGRESS).count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void replaysSuccessAfterRestartWithoutExecutingAgain() throws Exception {
        Path cache = temporaryFolder.newFile("success-cache.json").toPath();
        assertTrue(cache.toFile().delete());
        PrintJobReplayGuard firstProcess = new PrintJobReplayGuard(cache, TTL, 100, Clock.systemUTC());

        assertEquals(PrintJobReplayGuard.Action.EXECUTE, firstProcess.begin("job-1").action());
        firstProcess.succeed("job-1", success("job-1", "Kitchen Printer"));

        PrintJobReplayGuard restarted = new PrintJobReplayGuard(cache, TTL, 100, Clock.systemUTC());
        PrintJobReplayGuard.Decision replay = restarted.begin("job-1");

        assertEquals(PrintJobReplayGuard.Action.REPLAY_SUCCESS, replay.action());
        assertEquals("job-1", replay.cachedResult().id);
        assertEquals("Kitchen Printer", replay.cachedResult().printerName);
    }

    @Test
    public void restartConvertsPersistedPrintingToReconcileInsteadOfExecutingAgain() throws Exception {
        Path cache = temporaryFolder.newFile("printing-cache.json").toPath();
        assertTrue(cache.toFile().delete());
        PrintJobReplayGuard firstProcess = new PrintJobReplayGuard(cache, TTL, 100, Clock.systemUTC());

        assertEquals(PrintJobReplayGuard.Action.EXECUTE, firstProcess.begin("uncertain-job").action());

        PrintJobReplayGuard restarted = new PrintJobReplayGuard(cache, TTL, 100, Clock.systemUTC());
        PrintJobReplayGuard.Decision decision = restarted.begin("uncertain-job");

        assertEquals(PrintJobReplayGuard.Action.REPLAY_RECONCILE, decision.action());
        assertEquals("RECONCILE_REQUIRED", decision.cachedResult().state);
        assertEquals(true, decision.cachedResult().success);
    }

    @Test
    public void corruptDurableStateFailsClosedBeforePhysicalExecution() throws Exception {
        Path cache = temporaryFolder.newFile("corrupt-cache.json").toPath();
        Files.writeString(cache, "not-json");

        PrintJobReplayGuard guard = new PrintJobReplayGuard(cache, TTL, 100, Clock.systemUTC());

        assertEquals(PrintJobReplayGuard.Action.REJECT_PERSISTENCE, guard.begin("new-job").action());
    }

    @Test
    public void failedIdCanRetryAndDifferentIdsExecuteNormally() throws Exception {
        PrintJobReplayGuard guard = guard(Clock.systemUTC(), 100);

        assertEquals(PrintJobReplayGuard.Action.EXECUTE, guard.begin("job-failed").action());
        guard.fail("job-failed");
        assertEquals(PrintJobReplayGuard.Action.EXECUTE, guard.begin("job-failed").action());

        assertEquals(PrintJobReplayGuard.Action.EXECUTE, guard.begin("job-other").action());
    }

    @Test
    public void boundsInFlightJobsAndReleasesCapacityAfterAResult() throws Exception {
        PrintJobReplayGuard guard = guard(Clock.systemUTC(), 100, 10, 2);

        assertEquals(PrintJobReplayGuard.Action.EXECUTE, guard.begin("job-1").action());
        assertEquals(PrintJobReplayGuard.Action.EXECUTE, guard.begin("job-2").action());
        assertEquals(PrintJobReplayGuard.Action.REJECT_CAPACITY, guard.begin("job-3").action());
        assertEquals(2, guard.inFlightEntryCount());

        guard.fail("job-1");
        assertEquals(PrintJobReplayGuard.Action.EXECUTE, guard.begin("job-3").action());
        assertEquals(2, guard.inFlightEntryCount());
    }

    @Test
    public void reservesReconciliationCapacityBeforePhysicalExecution() throws Exception {
        Path cache = temporaryFolder.newFile("reconcile-capacity.json").toPath();
        assertTrue(cache.toFile().delete());
        PrintJobReplayGuard guard = new PrintJobReplayGuard(
                cache, TTL, 100, 10, 10, 1, Clock.systemUTC());

        assertEquals(PrintJobReplayGuard.Action.EXECUTE, guard.begin("job-1").action());
        assertEquals(PrintJobReplayGuard.Action.REJECT_CAPACITY, guard.begin("job-2").action());

        guard.succeed("job-1", success("job-1", "Printer"));
        assertEquals(PrintJobReplayGuard.Action.EXECUTE, guard.begin("job-2").action());
    }

    @Test
    public void boundsRememberedFailures() throws Exception {
        PrintJobReplayGuard guard = guard(Clock.systemUTC(), 100, 2, 1);

        for (int index = 1; index <= 3; index++) {
            String id = "failed-" + index;
            assertEquals(PrintJobReplayGuard.Action.EXECUTE, guard.begin(id).action());
            guard.fail(id);
        }

        assertEquals(2, guard.failedEntryCount());
        assertEquals(0, guard.inFlightEntryCount());
    }

    @Test
    public void aStalledSameIdRemainsSuppressedInsteadOfRiskingASecondPhysicalPrint() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-01T00:00:00Z"));
        PrintJobReplayGuard guard = guard(clock, 100, 10, 1);

        assertEquals(PrintJobReplayGuard.Action.EXECUTE, guard.begin("stalled-job").action());
        clock.advance(Duration.ofHours(25));

        assertEquals(PrintJobReplayGuard.Action.IN_PROGRESS, guard.begin("stalled-job").action());
        assertEquals(1, guard.inFlightEntryCount());
    }

    @Test
    public void replayPreservesTheCallerJobIdWhileUsingItsNormalizedKey() throws Exception {
        PrintJobReplayGuard guard = guard(Clock.systemUTC(), 100);

        assertEquals(PrintJobReplayGuard.Action.EXECUTE, guard.begin(" job-with-spaces ").action());
        guard.succeed(" job-with-spaces ", success(" job-with-spaces ", "Printer"));
        PrintJobReplayGuard.Decision replay = guard.begin(" job-with-spaces ");

        assertEquals(PrintJobReplayGuard.Action.REPLAY_SUCCESS, replay.action());
        assertEquals(" job-with-spaces ", replay.cachedResult().id);
    }

    @Test
    public void failedIdCanRetryAfterRestartBecauseFailuresAreNotPersisted() throws Exception {
        Path cache = temporaryFolder.newFile("failure-cache.json").toPath();
        assertTrue(cache.toFile().delete());
        PrintJobReplayGuard firstProcess = new PrintJobReplayGuard(cache, TTL, 100, Clock.systemUTC());
        assertEquals(PrintJobReplayGuard.Action.EXECUTE, firstProcess.begin("job-failed").action());
        firstProcess.fail("job-failed");

        PrintJobReplayGuard restarted = new PrintJobReplayGuard(cache, TTL, 100, Clock.systemUTC());

        assertEquals(PrintJobReplayGuard.Action.EXECUTE, restarted.begin("job-failed").action());
    }

    @Test
    public void rejectsNewPhysicalWorkAtTheSuccessLimitUntilEntriesExpire() throws Exception {
        Path cache = temporaryFolder.newFile("bounded-cache.json").toPath();
        assertTrue(cache.toFile().delete());
        MutableClock clock = new MutableClock(Instant.parse("2026-08-01T00:00:00Z"));
        PrintJobReplayGuard guard = new PrintJobReplayGuard(cache, TTL, 2, clock);

        succeed(guard, "job-1");
        clock.advance(Duration.ofMinutes(1));
        succeed(guard, "job-2");
        clock.advance(Duration.ofMinutes(1));
        assertEquals(PrintJobReplayGuard.Action.REJECT_CAPACITY, guard.begin("job-3").action());

        PrintJobReplayGuard boundedRestart = new PrintJobReplayGuard(cache, TTL, 2, clock);
        assertEquals(PrintJobReplayGuard.Action.REPLAY_SUCCESS, boundedRestart.begin("job-1").action());
        assertEquals(PrintJobReplayGuard.Action.REPLAY_SUCCESS, boundedRestart.begin("job-2").action());
        assertEquals(PrintJobReplayGuard.Action.REJECT_CAPACITY, boundedRestart.begin("job-3").action());

        clock.advance(Duration.ofHours(25));
        PrintJobReplayGuard expiredRestart = new PrintJobReplayGuard(cache, TTL, 2, clock);
        assertEquals(PrintJobReplayGuard.Action.EXECUTE, expiredRestart.begin("job-3").action());
    }

    @Test
    public void missingIdPreservesLegacyBypassBehavior() throws Exception {
        PrintJobReplayGuard guard = guard(Clock.systemUTC(), 100);

        assertEquals(PrintJobReplayGuard.Action.BYPASS, guard.begin(null).action());
        assertEquals(PrintJobReplayGuard.Action.BYPASS, guard.begin("  ").action());
    }

    @Test
    public void rejectsOversizedStableIdBeforeReservingPhysicalExecution() throws Exception {
        PrintJobReplayGuard guard = guard(Clock.systemUTC(), 100);
        String oversizedId = "x".repeat(PrintJobReplayGuard.MAX_STABLE_JOB_ID_LENGTH + 1);

        assertEquals(PrintJobReplayGuard.Action.REJECT_INVALID_ID, guard.begin(oversizedId).action());
        assertEquals(0, guard.inFlightEntryCount());
    }

    private PrintJobReplayGuard guard(Clock clock, int maxEntries) throws Exception {
        return guard(clock, maxEntries, PrintJobReplayGuard.DEFAULT_MAX_FAILED_ENTRIES,
                PrintJobReplayGuard.DEFAULT_MAX_IN_FLIGHT_ENTRIES);
    }

    private PrintJobReplayGuard guard(
            Clock clock,
            int maxSuccessEntries,
            int maxFailedEntries,
            int maxInFlightEntries
    ) throws Exception {
        Path cache = temporaryFolder.newFile().toPath();
        assertTrue(cache.toFile().delete());
        return new PrintJobReplayGuard(
                cache,
                TTL,
                maxSuccessEntries,
                maxFailedEntries,
                maxInFlightEntries,
                clock
        );
    }

    private void succeed(PrintJobReplayGuard guard, String id) {
        assertEquals(PrintJobReplayGuard.Action.EXECUTE, guard.begin(id).action());
        guard.succeed(id, success(id, "Printer"));
    }

    private PrintResult success(String id, String printerName) {
        return new PrintResult(true, "Success", id, printerName);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
