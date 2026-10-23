package tigerworkshop.webapphardwarebridge.services;

import javax.print.PrintException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.DeflaterOutputStream;

/** Strict adapter for the project's raster-only receipts, not a general ESC/POS interpreter. */
final class MacPos58RasterDocument {
    static final int WIDTH = 384;
    static final int ROW_BYTES = WIDTH / 8;
    static final int MAX_HEIGHT = 8192;
    private static final int TOP_MARGIN = 16;
    private static final int BOTTOM_MARGIN = 64;
    private static final int MAX_INPUT = 1024 * 1024;
    private static final byte[] PREFIX = {0x1b, 0x40, 0x1b, 0x61, 0, 0x1d, 0x76, 0x30};

    record Document(byte[] pdf, String media, boolean partialCut) {}
    private record Block(int sourceOffset, int height, int y) {}

    static boolean matches(byte[] input) {
        return input != null && input.length >= PREFIX.length
                && Arrays.equals(PREFIX, Arrays.copyOf(input, PREFIX.length));
    }

    static Document prepare(byte[] input) throws PrintException {
        if (!matches(input)) throw invalid("不是受支持的纯位图小票");
        if (input.length > MAX_INPUT) throw invalid("小票数据超过上限");
        List<Block> blocks = new ArrayList<>();
        int offset = 2; // The one allowed ESC @ at the beginning.
        int y = 0;
        boolean partialCut = false;
        while (offset < input.length) {
            int remaining = input.length - offset;
            if (remaining >= 3 && input[offset] == 0x1b && input[offset + 1] == 0x61) {
                if (input[offset + 2] != 0) throw invalid("仅支持左对齐的已栅格化内容");
                offset += 3;
            } else if (remaining >= 3 && input[offset] == 0x1b && input[offset + 1] == 0x4a) {
                y += Byte.toUnsignedInt(input[offset + 2]);
                offset += 3;
            } else if (remaining >= 3 && input[offset] == 0x1d
                    && input[offset + 1] == 0x76 && input[offset + 2] == 0x30) {
                if (remaining < 8) throw invalid("位图头不完整");
                if (input[offset + 3] != 0) throw invalid("不支持位图缩放模式");
                int widthBytes = u16(input, offset + 4);
                int height = u16(input, offset + 6);
                if (widthBytes != ROW_BYTES || height == 0 || height > MAX_HEIGHT)
                    throw invalid("仅支持384点宽且高度有界的POS-58位图");
                int length = widthBytes * height;
                if (remaining - 8 < length) throw invalid("位图数据不完整");
                if (blocks.size() >= 512) throw invalid("位图块过多");
                // The validated PPD is 136pt wide (383.44 dots). Never clip a black last dot.
                for (int row = 0; row < height; row++) {
                    if ((input[offset + 8 + row * ROW_BYTES + ROW_BYTES - 1] & 1) != 0)
                        throw invalid("最右侧黑点超出当前POS-58驱动可打印宽度");
                }
                blocks.add(new Block(offset + 8, height, y));
                y += height;
                offset += 8 + length;
            } else if (remaining == 4 && input[offset] == 0x1d && input[offset + 1] == 0x56
                    && input[offset + 2] == 0x42 && input[offset + 3] == 0) {
                partialCut = true;
                offset += 4;
            } else {
                throw invalid("含未支持的文本、指令或截断数据（偏移" + offset + "）");
            }
            if (y > MAX_HEIGHT) throw invalid("小票累计高度超过上限");
        }
        if (blocks.isEmpty()) throw invalid("没有完整位图");
        int imageHeight = y + TOP_MARGIN + BOTTOM_MARGIN;
        byte[] packed = new byte[ROW_BYTES * imageHeight];
        for (Block block : blocks) {
            System.arraycopy(input, block.sourceOffset, packed,
                    (block.y + TOP_MARGIN) * ROW_BYTES, block.height * ROW_BYTES);
        }
        // Only used with the fingerprinted filter, which drops its final
        // nonblank row. It consumes this duplicate in existing trailing space
        // so all intended dots remain in the final driver output.
        int lastBlackRow = -1;
        for (int i = packed.length - 1; i >= 0; i--) {
            if (packed[i] != 0) { lastBlackRow = i / ROW_BYTES; break; }
        }
        if (lastBlackRow >= 0) {
            System.arraycopy(packed, lastBlackRow * ROW_BYTES, packed,
                    (lastBlackRow + 1) * ROW_BYTES, ROW_BYTES);
        }
        double pageHeight = imageHeight * 72.0 / 203.0;
        try {
            return new Document(pdf(packed, imageHeight, pageHeight),
                    String.format(Locale.ROOT, "Custom.136x%.9fpt", pageHeight), partialCut);
        } catch (IOException error) {
            throw new PrintException("Mac位图文档生成失败，尚未提交打印", error);
        }
    }

    private static int u16(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | (Byte.toUnsignedInt(bytes[offset + 1]) << 8);
    }

    private static PrintException invalid(String reason) {
        return new PrintException("Mac POS-58兼容模式：" + reason + "；尚未提交打印，未自动切换RAW");
    }

    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

    private static byte[] pdf(byte[] packed, int height, double pageHeight) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(packed);
        }
        byte[] image = compressed.toByteArray();
        byte[] drawing = ascii(String.format(Locale.ROOT,
                "q %.9f 0 0 %.9f 0 0 cm /Im0 Do Q\n", WIDTH * 72.0 / 203.0, pageHeight));
        List<byte[]> objects = List.of(
                ascii("<< /Type /Catalog /Pages 2 0 R >>"),
                ascii("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                ascii(String.format(Locale.ROOT,
                        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 136 %.9f] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>", pageHeight)),
                stream("/Type /XObject /Subtype /Image /Width 384 /Height " + height
                        + " /ColorSpace /DeviceGray /BitsPerComponent 1 /Decode [1 0]"
                        + " /Interpolate false /Filter /FlateDecode ", image),
                stream("", drawing));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(ascii("%PDF-1.4\n"));
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(output.size());
            output.write(ascii((i + 1) + " 0 obj\n"));
            output.write(objects.get(i));
            output.write(ascii("\nendobj\n"));
        }
        int xref = output.size();
        output.write(ascii("xref\n0 6\n0000000000 65535 f \n"));
        for (int position : offsets)
            output.write(ascii(String.format(Locale.ROOT, "%010d 00000 n \n", position)));
        output.write(ascii("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n"));
        return output.toByteArray();
    }

    private static byte[] stream(String dictionary, byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(ascii("<< " + dictionary + "/Length " + content.length + " >>\nstream\n"));
        output.write(content);
        output.write(ascii("\nendstream"));
        return output.toByteArray();
    }
}
