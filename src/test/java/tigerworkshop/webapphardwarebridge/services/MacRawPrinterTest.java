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
import java.util.concurrent.atomic.AtomicReference;
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

    @Test public void selectedPos58QueueUsesOnePdfDocumentSubmissionAndDeletesOwnFile() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger validated = new AtomicInteger();
        AtomicReference<Path> spool = new AtomicReference<>();
        new MacRawPrinter((args, timeout) -> {
            calls.incrementAndGet();
            assertFalse(args.contains("raw"));
            assertTrue(args.contains("media=Custom.136x30.857142857pt"));
            assertTrue(args.contains("DocCutType=1PartialCutDoc"));
            assertEquals("1", args.get(args.indexOf("-#") + 1));
            Path file = Path.of(args.get(args.size() - 1));
            spool.set(file);
            assertTrue(file.toString().endsWith(".pdf"));
            assertTrue(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1).startsWith("%PDF-1.4"));
            return 0;
        }, "XP58", queue -> validated.incrementAndGet())
                .submit(printer("ipp://localhost/printers/XP58"), MacPos58RasterDocumentTest.receipt(true));
        assertEquals(1, calls.get());
        assertEquals(1, validated.get());
        assertFalse(Files.exists(spool.get()));
    }

    @Test public void defaultOtherQueueAndNativeTextKeepOriginalRawBytes() throws Exception {
        byte[] raster = MacPos58RasterDocumentTest.receipt(false);
        for (String selected : new String[]{"", "another_queue"}) {
            new MacRawPrinter((args, timeout) -> {
                assertTrue(args.contains("raw"));
                assertArrayEquals(raster, Files.readAllBytes(Path.of(args.get(args.size() - 1))));
                return 0;
            }, selected, queue -> fail("Unselected queue must not validate driver"))
                    .submit(printer("ipp://localhost/printers/XP58"), raster);
        }
        byte[] nativeText = {0x1b, 0x40, 65, 10};
        new MacRawPrinter((args, timeout) -> {
            assertTrue(args.contains("raw"));
            assertArrayEquals(nativeText, Files.readAllBytes(Path.of(args.get(args.size() - 1))));
            return 0;
        }, "XP58", queue -> fail("Native text must not use raster profile"))
                .submit(printer("ipp://localhost/printers/XP58"), nativeText);
    }

    @Test public void manualTearSkipsOnlyDriverCutFeedForSelectedRasterQueue() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        new MacRawPrinter((args, timeout) -> {
            calls.incrementAndGet();
            assertFalse(args.contains("raw"));
            assertTrue(args.contains("DocCutType=0NoCutDoc"));
            assertFalse(args.contains("DocCutType=1PartialCutDoc"));
            assertTrue(args.contains("FeedCutAfterJobEnd=0None"));
            assertEquals("1", args.get(args.indexOf("-#") + 1));
            return 0;
        }, "XP58", queue -> {}, true)
                .submit(printer("ipp://localhost/printers/XP58"), MacPos58RasterDocumentTest.receipt(true));
        assertEquals(1, calls.get());
    }

    @Test public void driverValidationAndMalformedRasterFailBeforeSubmission() throws Exception {
        MacRawPrinter.CommandRunner forbidden = (args, timeout) -> { fail("Must not submit"); return 0; };
        byte[] receipt = MacPos58RasterDocumentTest.receipt(false);
        assertThrows(Exception.class, () -> new MacRawPrinter(forbidden, "XP58", queue -> {
            throw new javax.print.PrintException("Wrong driver");
        }).submit(printer("ipp://localhost/printers/XP58"), receipt));
        assertThrows(Exception.class, () -> new MacRawPrinter(forbidden, "XP58", queue -> {})
                .submit(printer("ipp://localhost/printers/XP58"), java.util.Arrays.copyOf(receipt, receipt.length - 1)));
    }

    @Test public void driverSubmissionFailureNeverFallsBackOrRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(MacRawPrinter.SubmissionUnknownException.class,
                () -> new MacRawPrinter((args, timeout) -> { calls.incrementAndGet(); return 1; }, "XP58", queue -> {})
                        .submit(printer("ipp://localhost/printers/XP58"), MacPos58RasterDocumentTest.receipt(true)));
        assertEquals(1, calls.get());
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
