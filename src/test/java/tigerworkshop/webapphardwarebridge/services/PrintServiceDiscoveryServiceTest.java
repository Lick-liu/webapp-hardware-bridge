package tigerworkshop.webapphardwarebridge.services;

import org.junit.Test;
import tigerworkshop.webapphardwarebridge.dtos.PrintServiceDTO;

import javax.print.PrintService;
import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class PrintServiceDiscoveryServiceTest {
    @Test
    public void listPrintersMergesJavaAndWindowsRedirectedPrinters() {
        PrintServiceDiscoveryService service = new PrintServiceDiscoveryService(
                () -> new PrintService[]{fakePrintService("Receipt Printer")},
                () -> new PrintService[]{fakePrintService("Office Laser")},
                () -> Arrays.asList(
                        new PrintServiceDTO("Office Laser", "Win32 printer"),
                        new PrintServiceDTO("HP LaserJet (redirected 2)", "Win32 redirected printer")
                ),
                () -> "Windows 11"
        );

        List<PrintServiceDTO> printers = service.listPrinters();

        assertEquals(
                Arrays.asList("Receipt Printer", "Office Laser", "HP LaserJet (redirected 2)"),
                printerNames(printers)
        );
    }

    @Test
    public void findPrintServiceByNameUsesBothJavaLookupPathsCaseInsensitively() {
        PrintService redirectedPrinter = fakePrintService("HP LaserJet (redirected 2)");
        PrintServiceDiscoveryService service = new PrintServiceDiscoveryService(
                () -> new PrintService[]{fakePrintService("Receipt Printer")},
                () -> new PrintService[]{redirectedPrinter},
                () -> Arrays.asList(new PrintServiceDTO("Ignored", "Windows only")),
                () -> "Windows 11"
        );

        Optional<PrintService> printService = service.findPrintServiceByName("hp laserjet (REDIRECTED 2)");

        assertTrue(printService.isPresent());
        assertSame(redirectedPrinter, printService.get());
    }

    @Test
    public void listPrintersDoesNotRunWindowsLookupOutsideWindows() {
        AtomicBoolean windowsLookupCalled = new AtomicBoolean(false);
        PrintServiceDiscoveryService service = new PrintServiceDiscoveryService(
                () -> new PrintService[]{fakePrintService("Receipt Printer")},
                () -> new PrintService[0],
                () -> {
                    windowsLookupCalled.set(true);
                    return Arrays.asList(new PrintServiceDTO("Should Not Appear", "Windows"));
                },
                () -> "Linux"
        );

        List<PrintServiceDTO> printers = service.listPrinters();

        assertEquals(Arrays.asList("Receipt Printer"), printerNames(printers));
        assertFalse(windowsLookupCalled.get());
    }

    @Test
    public void listPrintersMergesWindowsVirtualPdfPrintersFromSecondaryLookupSource() {
        PrintServiceDiscoveryService service = new PrintServiceDiscoveryService(
                () -> new PrintService[0],
                () -> new PrintService[0],
                Arrays.asList(
                        () -> Arrays.asList(new PrintServiceDTO("XP58 (已重定向 1)", "Win32 printer")),
                        () -> Arrays.asList(
                                new PrintServiceDTO("导出为WPS PDF", "registry virtual printer"),
                                new PrintServiceDTO("Microsoft Print to PDF", "registry virtual printer")
                        )
                ),
                () -> "Windows 11"
        );

        List<PrintServiceDTO> printers = service.listPrinters();

        assertEquals(
                Arrays.asList("XP58 (已重定向 1)", "导出为WPS PDF", "Microsoft Print to PDF"),
                printerNames(printers)
        );
    }

    @Test
    public void listPrintersContinuesWhenOneWindowsLookupFails() {
        PrintServiceDiscoveryService service = new PrintServiceDiscoveryService(
                () -> new PrintService[0],
                () -> new PrintService[0],
                Arrays.asList(
                        () -> {
                            throw new IllegalStateException("Win32 printer lookup failed");
                        },
                        () -> Arrays.asList(
                                new PrintServiceDTO("导出为WPS PDF", "registry virtual printer"),
                                new PrintServiceDTO("Microsoft Print to PDF", "registry virtual printer")
                        )
                ),
                () -> "Windows 11"
        );

        List<PrintServiceDTO> printers = service.listPrinters();

        assertEquals(
                Arrays.asList("导出为WPS PDF", "Microsoft Print to PDF"),
                printerNames(printers)
        );
    }

    private static List<String> printerNames(List<PrintServiceDTO> printers) {
        return printers.stream().map(printer -> printer.name).collect(Collectors.toList());
    }

    private static PrintService fakePrintService(String name) {
        return (PrintService) Proxy.newProxyInstance(
                PrintService.class.getClassLoader(),
                new Class[]{PrintService.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) {
                        return name;
                    }
                    if ("toString".equals(method.getName())) {
                        return name;
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }

                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType.equals(Boolean.TYPE)) {
            return false;
        }
        if (returnType.equals(Integer.TYPE)) {
            return 0;
        }
        if (returnType.equals(Long.TYPE)) {
            return 0L;
        }
        if (returnType.equals(Void.TYPE)) {
            return null;
        }
        if (returnType.isArray()) {
            return Array.newInstance(returnType.getComponentType(), 0);
        }

        return null;
    }
}
