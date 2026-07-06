package tigerworkshop.webapphardwarebridge.services;

import java.awt.*;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class VirtualPdfPrinterSupport {
    private static final Pattern PDF_PRINTER_NAME = Pattern.compile(".*(print\\s*to\\s*pdf|wps\\s*pdf|pdf).*", Pattern.CASE_INSENSITIVE);
    private static final int DEFAULT_FONT_SIZE = 10;

    public static boolean isVirtualPdfPrinter(String printerName) {
        if (printerName == null || printerName.trim().isEmpty()) {
            return false;
        }

        return PDF_PRINTER_NAME.matcher(printerName.toLowerCase(Locale.ROOT)).matches();
    }

    public static Printable createRawTextPrintable(byte[] rawBytes) {
        return new RawTextPrintable(toPrintableTextLines(rawBytes, bestEffortCharset(rawBytes)));
    }

    public static List<String> toPrintableTextLines(byte[] rawBytes, Charset charset) {
        byte[] cleanedBytes = stripEscPosCommands(rawBytes);
        String text = new String(cleanedBytes, charset)
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        List<String> lines = text.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toList());

        if (lines.isEmpty()) {
            lines.add("(empty raw print job)");
        }

        return lines;
    }

    private static Charset bestEffortCharset(byte[] rawBytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(stripEscPosCommands(rawBytes)));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            return Charset.forName("GBK");
        }
    }

    private static byte[] stripEscPosCommands(byte[] rawBytes) {
        ByteArrayOutputStream printableBytes = new ByteArrayOutputStream();
        if (rawBytes == null) {
            return printableBytes.toByteArray();
        }

        for (int index = 0; index < rawBytes.length; index += 1) {
            int value = rawBytes[index] & 0xFF;
            if (value == 0x1B) {
                index = skipEscCommand(rawBytes, index);
                continue;
            }
            if (value == 0x1D) {
                index = skipGsCommand(rawBytes, index);
                continue;
            }
            if (value < 0x20 && value != '\n' && value != '\r' && value != '\t') {
                continue;
            }

            printableBytes.write(rawBytes[index]);
        }

        return printableBytes.toByteArray();
    }

    private static int skipEscCommand(byte[] rawBytes, int index) {
        if (index + 1 >= rawBytes.length) {
            return index;
        }

        int command = rawBytes[index + 1] & 0xFF;
        if (command == 'a' || command == '!' || command == 'E' || command == 'J'
                || command == 'd' || command == 'M' || command == 't' || command == '-'
                || command == '3') {
            return Math.min(index + 2, rawBytes.length - 1);
        }
        if (command == '$' || command == '\\') {
            return Math.min(index + 3, rawBytes.length - 1);
        }

        return index + 1;
    }

    private static int skipGsCommand(byte[] rawBytes, int index) {
        if (index + 1 >= rawBytes.length) {
            return index;
        }

        int command = rawBytes[index + 1] & 0xFF;
        if (command == '!' || command == 'B') {
            return Math.min(index + 2, rawBytes.length - 1);
        }
        if (command == 'V') {
            if (index + 2 < rawBytes.length && ((rawBytes[index + 2] & 0xFF) == 0x41 || (rawBytes[index + 2] & 0xFF) == 0x42)) {
                return Math.min(index + 3, rawBytes.length - 1);
            }

            return Math.min(index + 2, rawBytes.length - 1);
        }

        return index + 1;
    }

    private static class RawTextPrintable implements Printable {
        private final List<String> lines;

        RawTextPrintable(List<String> lines) {
            this.lines = new ArrayList<>(lines);
        }

        @Override
        public int print(Graphics graphics, PageFormat pageFormat, int pageIndex) throws PrinterException {
            Graphics2D graphics2D = (Graphics2D) graphics;
            graphics2D.setColor(Color.BLACK);
            graphics2D.setFont(new Font(Font.MONOSPACED, Font.PLAIN, DEFAULT_FONT_SIZE));

            FontMetrics fontMetrics = graphics2D.getFontMetrics();
            int lineHeight = fontMetrics.getHeight();
            int linesPerPage = Math.max(1, (int) (pageFormat.getImageableHeight() / lineHeight));
            int firstLineIndex = pageIndex * linesPerPage;

            if (firstLineIndex >= lines.size()) {
                return NO_SUCH_PAGE;
            }

            int x = (int) pageFormat.getImageableX();
            int y = (int) pageFormat.getImageableY() + fontMetrics.getAscent();

            for (int lineIndex = firstLineIndex; lineIndex < Math.min(firstLineIndex + linesPerPage, lines.size()); lineIndex += 1) {
                graphics2D.drawString(lines.get(lineIndex), x, y);
                y += lineHeight;
            }

            return PAGE_EXISTS;
        }
    }
}
