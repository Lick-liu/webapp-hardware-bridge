package tigerworkshop.webapphardwarebridge.services;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tigerworkshop.webapphardwarebridge.dtos.LocalPrintDeviceContextDTO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
        assertTrue(Files.isRegularFile(contextFile));

        LocalPrintDeviceContextService restarted = new LocalPrintDeviceContextService(contextFile);
        assertEquals(first.getDeviceId(), restarted.getContext("17657").getDeviceId());
    }

    @Test
    public void activationCursorUsesFirstWriteWinsAndSurvivesRestart() throws Exception {
        Path contextFile = missingContextFile();
        LocalPrintDeviceContextService service = new LocalPrintDeviceContextService(contextFile);

        LocalPrintDeviceContextDTO first = service.activate("17657", 31048L);
        LocalPrintDeviceContextDTO repeated = service.activate("17657", 99999L);

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

        service.activate("17657", 31048L);
        LocalPrintDeviceContextDTO other = service.activate("17658", 41048L);

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
        assertThrows(IllegalArgumentException.class, () -> service.activate("17657", -1L));
        assertThrows(IllegalArgumentException.class, () -> service.activate("17657", null));
    }

    private Path missingContextFile() throws IOException {
        Path file = temporaryFolder.newFile("local-print-device-context.json").toPath();
        assertTrue(file.toFile().delete());
        return file;
    }
}
