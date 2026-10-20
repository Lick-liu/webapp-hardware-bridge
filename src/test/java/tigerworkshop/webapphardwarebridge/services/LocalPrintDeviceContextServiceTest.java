package tigerworkshop.webapphardwarebridge.services;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tigerworkshop.webapphardwarebridge.dtos.LocalPrintDeviceContextDTO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LocalPrintDeviceContextServiceTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void missingFileCreatesOneDurableDeviceIdentity() throws Exception {
        Path contextFile = missingContextFile();
        LocalPrintDeviceContextService service = new LocalPrintDeviceContextService(contextFile);

        LocalPrintDeviceContextDTO first = service.getContext("17657");
        LocalPrintDeviceContextDTO second = service.getContext("17657");

        assertTrue(first.getDeviceId().startsWith("local-print-device-"));
        assertEquals(first.getDeviceId(), second.getDeviceId());
        assertEquals("17657", first.getShopId());
        assertNull(first.getActivationTaskId());
        assertTrue(first.getActivationToken().matches("[0-9a-f-]{36}"));
        assertEquals(first.getActivationToken(), second.getActivationToken());
        assertTrue(Files.isRegularFile(contextFile));

        LocalPrintDeviceContextService restarted = new LocalPrintDeviceContextService(contextFile);
        assertEquals(first.getDeviceId(), restarted.getContext("17657").getDeviceId());
    }

    @Test
    public void activationCursorUsesFirstWriteWinsAndSurvivesRestart() throws Exception {
        Path contextFile = missingContextFile();
        LocalPrintDeviceContextService service = new LocalPrintDeviceContextService(contextFile);

        LocalPrintDeviceContextDTO context = service.getContext("17657");
        LocalPrintDeviceContextDTO first = service.activate(
                "17657", 31048L, context.getActivationToken());
        LocalPrintDeviceContextDTO repeated = service.activate(
                "17657", 99999L, first.getActivationToken());

        assertEquals(Long.valueOf(31048L), first.getActivationTaskId());
        assertEquals(Long.valueOf(31048L), repeated.getActivationTaskId());
        assertEquals(Long.valueOf(31048L),
                new LocalPrintDeviceContextService(contextFile)
                        .getContext("17657").getActivationTaskId());
    }

    @Test
    public void differentShopsKeepIndependentActivationCursors() throws Exception {
        LocalPrintDeviceContextService service =
                new LocalPrintDeviceContextService(missingContextFile());

        service.activate("17657", 31048L, tokenFor(service, "17657"));
        LocalPrintDeviceContextDTO other = service.activate(
                "17658", 41048L, tokenFor(service, "17658"));

        assertEquals(Long.valueOf(31048L), service.getContext("17657").getActivationTaskId());
        assertEquals(Long.valueOf(41048L), other.getActivationTaskId());
        assertEquals(service.getContext("17657").getDeviceId(), other.getDeviceId());
    }

    @Test
    public void corruptFileFailsClosedAndIsNotOverwritten() throws Exception {
        Path contextFile = missingContextFile();
        Files.writeString(contextFile, "not-json");
        LocalPrintDeviceContextService service = new LocalPrintDeviceContextService(contextFile);

        assertThrows(IOException.class, () -> service.getContext("17657"));

        assertEquals("not-json", Files.readString(contextFile));
    }

    @Test
    public void unwritableLocationDoesNotCreateMemoryOnlyFallbackIdentity() throws Exception {
        Path missingParent = temporaryFolder.getRoot().toPath()
                .resolve("missing-parent")
                .resolve("local-print-device-context.json");
        LocalPrintDeviceContextService service = new LocalPrintDeviceContextService(missingParent);

        assertThrows(IOException.class, () -> service.getContext("17657"));
        assertThrows(IOException.class, () -> service.getContext("17657"));

        assertFalse(Files.exists(missingParent));
    }

    @Test
    public void rejectsInvalidShopAndCursorValues() throws Exception {
        LocalPrintDeviceContextService service =
                new LocalPrintDeviceContextService(missingContextFile());

        assertThrows(IllegalArgumentException.class, () -> service.getContext("../17657"));
        assertThrows(IllegalArgumentException.class,
                () -> service.activate("17657", -1L, tokenFor(service, "17657")));
        assertThrows(IllegalArgumentException.class,
                () -> service.activate("17657", null, tokenFor(service, "17657")));
    }

    @Test
    public void activationCredentialRejectsMissingCrossShopAndReplay() throws Exception {
        LocalPrintDeviceContextService service =
                new LocalPrintDeviceContextService(missingContextFile());
        String shopToken = tokenFor(service, "17657");
        String otherShopToken = tokenFor(service, "17658");

        assertThrows(LocalPrintDeviceContextService.InvalidActivationTokenException.class,
                () -> service.activate("17657", 31048L, null));
        assertThrows(LocalPrintDeviceContextService.InvalidActivationTokenException.class,
                () -> service.activate("17657", 31048L, otherShopToken));

        service.activate("17657", 31048L, shopToken);
        assertThrows(LocalPrintDeviceContextService.InvalidActivationTokenException.class,
                () -> service.activate("17657", 31048L, shopToken));
    }

    @Test
    public void activationCredentialExpires() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-16T12:00:00Z"));
        LocalPrintDeviceContextService service =
                new LocalPrintDeviceContextService(missingContextFile(), clock);
        String token = tokenFor(service, "17657");

        clock.advance(Duration.ofMinutes(6));

        assertThrows(LocalPrintDeviceContextService.InvalidActivationTokenException.class,
                () -> service.activate("17657", 31048L, token));
    }

    @Test
    public void correctionIsExplicitAndRequiresFreshCredential() throws Exception {
        LocalPrintDeviceContextService service =
                new LocalPrintDeviceContextService(missingContextFile());
        LocalPrintDeviceContextDTO activated = service.activate(
                "17657", 31048L, tokenFor(service, "17657"));
        LocalPrintDeviceContextDTO firstWriteStillWins = service.activate(
                "17657", 99999L, activated.getActivationToken());

        assertEquals(Long.valueOf(31048L), firstWriteStillWins.getActivationTaskId());

        LocalPrintDeviceContextDTO corrected = service.correctActivation(
                "17657", 31049L, firstWriteStillWins.getActivationToken());
        assertEquals(Long.valueOf(31049L), corrected.getActivationTaskId());
        assertThrows(LocalPrintDeviceContextService.InvalidActivationTokenException.class,
                () -> service.correctActivation(
                        "17657", 31050L, firstWriteStillWins.getActivationToken()));
    }

    private String tokenFor(LocalPrintDeviceContextService service, String shopId)
            throws IOException {
        return service.getContext(shopId).getActivationToken();
    }

    private Path missingContextFile() throws IOException {
        Path file = temporaryFolder.newFile("local-print-device-context.json").toPath();
        assertTrue(file.toFile().delete());
        return file;
    }

    private static class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
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
