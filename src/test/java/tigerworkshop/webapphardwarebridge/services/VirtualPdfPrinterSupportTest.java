package tigerworkshop.webapphardwarebridge.services;

import org.apache.commons.codec.binary.Base64;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

public class VirtualPdfPrinterSupportTest {
    @Test
    public void detectsWindowsVirtualPdfPrinters() {
        assertTrue(VirtualPdfPrinterSupport.isVirtualPdfPrinter("Microsoft Print to PDF"));
        assertTrue(VirtualPdfPrinterSupport.isVirtualPdfPrinter("导出为WPS PDF"));
        assertFalse(VirtualPdfPrinterSupport.isVirtualPdfPrinter("XP58 (已重定向 1)"));
    }

    @Test
    public void convertsEscPosRawBytesToPrintableTextLines() {
        byte[] rawBytes = Base64.decodeBase64("G0AbQBthAEhlbGxvIFdvcmxkCh0hERthAUVTQy9QT1MgUHJpbnRlciBUZXN0Ch0hABthAkdvb2RieWUgV29ybGQKHVZBAw==");

        assertEquals(
                Arrays.asList("Hello World", "ESC/POS Printer Test", "Goodbye World"),
                VirtualPdfPrinterSupport.toPrintableTextLines(rawBytes, StandardCharsets.UTF_8)
        );
    }

    @Test
    public void keepsPlainRawTextPrintable() {
        byte[] rawBytes = "Line 1\r\nLine 2\nLine 3".getBytes(StandardCharsets.UTF_8);

        assertEquals(
                Arrays.asList("Line 1", "Line 2", "Line 3"),
                VirtualPdfPrinterSupport.toPrintableTextLines(rawBytes, StandardCharsets.UTF_8)
        );
    }
}
