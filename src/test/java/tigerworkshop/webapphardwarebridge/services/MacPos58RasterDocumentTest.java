package tigerworkshop.webapphardwarebridge.services;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.Test;

import javax.print.PrintException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Locale;
import static org.junit.Assert.*;

public class MacPos58RasterDocumentTest {
    static byte[] receipt(boolean cut) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x1b, 0x40, 0x1b, 0x61, 0});
        block(out);
        out.write(new byte[]{0x1b, 0x4a, 3, 0x1b, 0x61, 0});
        block(out);
        if (cut) out.write(new byte[]{0x1d, 0x56, 0x42, 0});
        return out.toByteArray();
    }

    private static void block(ByteArrayOutputStream out) throws Exception {
        out.write(new byte[]{0x1d, 0x76, 0x30, 0, 48, 0, 2, 0});
        byte[] pixels = new byte[96];
        pixels[1] = (byte) 0x81;
        pixels[48 + 2] = 0x40;
        out.write(pixels);
    }

    @Test public void preservesPackedDotsBothImagesAndFeedSpacingInReadablePdf() throws Exception {
        MacPos58RasterDocument.Document result = MacPos58RasterDocument.prepare(receipt(true));
        assertTrue(result.partialCut());
        try (PDDocument pdf = PDDocument.load(result.pdf())) {
            assertEquals(1, pdf.getNumberOfPages());
            assertEquals(136.0, pdf.getPage(0).getMediaBox().getWidth(), 0.001);
            PDImageXObject object = (PDImageXObject) pdf.getPage(0).getResources().getXObject(COSName.getPDFName("Im0"));
            BufferedImage image = object.getImage();
            assertEquals(384, image.getWidth());
            assertEquals(87, image.getHeight());
            int blackCount = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    boolean expected = ((y == 16 || y == 21) && (x == 8 || x == 15))
                            || ((y == 17 || y == 22 || y == 23) && x == 17);
                    boolean actual = (image.getRGB(x, y) & 0xffffff) == 0;
                    assertEquals("dot " + x + "," + y, expected, actual);
                    if (actual) blackCount++;
                }
            }
            assertEquals(7, blackCount); // Six intended dots plus the consumed last-row guard.
        }
    }

    @Test public void rejectsTruncatedHeaderOrPixelsBeforeCreatingDocument() throws Exception {
        byte[] input = receipt(false);
        for (int length : new int[]{8, 9, 10, 11, 12, input.length - 1}) {
            assertThrows(PrintException.class, () -> MacPos58RasterDocument.prepare(Arrays.copyOf(input, length)));
        }
    }

    @Test public void rejectsUnsupportedWidthModeAlignmentAndClippedBlackColumn() throws Exception {
        for (int[] mutation : new int[][]{{9, 72}, {8, 1}, {4, 1}, {13 + 47, 1}}) {
            byte[] input = receipt(false);
            input[mutation[0]] = (byte) mutation[1];
            assertThrows(PrintException.class, () -> MacPos58RasterDocument.prepare(input));
        }
    }

    @Test public void rejectsUnknownTrailingTextAndCutInsteadOfSilentlyDroppingIt() throws Exception {
        for (byte[] suffix : new byte[][]{{65}, {0x1b, 0x40}, {0x1d, 0x56, 0}}) {
            byte[] input = receipt(false);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(input); out.write(suffix);
            assertThrows(PrintException.class, () -> MacPos58RasterDocument.prepare(out.toByteArray()));
        }
    }

    @Test public void rejectsOversizedInputAndCumulativeFeedHeight() throws Exception {
        byte[] input = Arrays.copyOf(receipt(false), 1024 * 1024 + 1);
        assertThrows(PrintException.class, () -> MacPos58RasterDocument.prepare(input));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(receipt(false));
        for (int i = 0; i < 33; i++) out.write(new byte[]{0x1b, 0x4a, (byte) 255});
        assertThrows(PrintException.class, () -> MacPos58RasterDocument.prepare(out.toByteArray()));
    }

    @Test public void usesLocaleIndependentMediaAndPreservesNoCutIntent() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            MacPos58RasterDocument.Document document = MacPos58RasterDocument.prepare(receipt(false));
            assertFalse(document.partialCut());
            assertEquals("Custom.136x30.857142857pt", document.media());
        } finally { Locale.setDefault(previous); }
    }

    @Test public void doesNotClassifyNativeTextAsProjectRaster() {
        assertFalse(MacPos58RasterDocument.matches(new byte[]{0x1b, 0x40, 65, 10}));
        assertFalse(MacPos58RasterDocument.matches(null));
    }
}
