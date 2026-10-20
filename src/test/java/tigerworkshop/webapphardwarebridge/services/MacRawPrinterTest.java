package tigerworkshop.webapphardwarebridge.services;

import org.junit.Test;
import javax.print.PrintService;
import javax.print.attribute.standard.PrinterURI;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class MacRawPrinterTest {
    private PrintService printer(String uri) {
        return (PrintService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{PrintService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> uri == null ? null : new PrinterURI(URI.create(uri));
                    case "getName" -> "Printer USB Printer Port";
                    default -> throw new AssertionError("Unexpected Java print fallback: " + method);
                });
    }

    @Test public void preservesBinaryAndUsesCupsDestinationInsteadOfDisplayName() throws Exception {
        byte[] bytes = {0x1b, 0x40, 0, (byte) 0xff, (byte) 0xd6, (byte) 0xd0, 10};
        AtomicInteger calls = new AtomicInteger();
        new MacRawPrinter((args, timeout) -> {
            calls.incrementAndGet();
            assertEquals(List.of("/usr/bin/lpr", "-H", "localhost:631", "-P", "Printer_USB_Printer_Port", "-o", "raw",
                    "-o", "job-sheets=none"), args.subList(0, 9));
            assertArrayEquals(bytes, Files.readAllBytes(Path.of(args.get(9))));
            return 0;
        }).submit(printer("ipp://localhost/printers/Printer_USB_Printer_Port"), bytes);
        assertEquals(1, calls.get());
    }

    @Test public void rejectsMissingAmbiguousAndRemoteDestinationsBeforeSubmission() throws Exception {
        for (String uri : new String[]{null, "ipp://remote/printers/P", "ipp://localhost/printers/P%2FQ",
                "ipp://localhost/printers/-P", "ipp://localhost/printers/P%20Q", "usb://Printer/Port"}) {
            assertThrows(Exception.class, () -> new MacRawPrinter((args, timeout) -> {
                fail("Must not submit an ambiguous destination"); return 0;
            }).submit(printer(uri), new byte[]{1}));
        }
    }

    @Test public void nonzeroExitIsNotReportedAsSuccessOrRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(MacRawPrinter.SubmissionUnknownException.class, () -> new MacRawPrinter((args, timeout) -> {
            calls.incrementAndGet(); return 1;
        }).submit(printer("ipp://127.0.0.1:631/printers/XP58"), new byte[]{1}));
        assertEquals(1, calls.get());
    }

    @Test public void startedProcessTimeoutIsUnknown() {
        assertThrows(MacRawPrinter.SubmissionUnknownException.class, () -> new MacRawPrinter((args, timeout) -> {
            throw new MacRawPrinter.SubmissionUnknownException("timeout");
        }).submit(printer("ipp://localhost/printers/XP58"), new byte[]{1}));
    }

    @Test public void processRunnerReportsExitAndBoundsWaiting() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        assertEquals(7, MacRawPrinter.runProcess(List.of(java, "-cp", classes, Child.class.getName(), "exit"), Duration.ofSeconds(10)));
        assertThrows(MacRawPrinter.SubmissionUnknownException.class,
                () -> MacRawPrinter.runProcess(List.of(java, "-cp", classes, Child.class.getName(), "wait"), Duration.ofMillis(100)));
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("wait")) Thread.sleep(30_000);
            System.exit(7);
        }
    }
}
