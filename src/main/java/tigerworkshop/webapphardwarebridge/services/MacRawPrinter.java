package tigerworkshop.webapphardwarebridge.services;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.attribute.standard.PrinterURI;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Byte-preserving RAW by default; an explicit POS-58 profile can convert project raster receipts. */
public final class MacRawPrinter {
    private static final Logger log = LogManager.getLogger(MacRawPrinter.class);
    static final String POS58_QUEUE_PROPERTY = "tea.print.macPos58RasterQueue";
    static final String POS58_MANUAL_TEAR_PROPERTY = "tea.print.macPos58ManualTear";
    private static final String POS58_DRIVER_SHA256 = "5d48e87ecb5428e8d6f8224df3c82cbcd5ad62f5bb9f8809be7bf2e899576ca6";

    @FunctionalInterface
    interface QueueValidator {
        void validate(String queue) throws Exception;
    }
    @FunctionalInterface
    interface CommandRunner {
        int run(List<String> command, Duration timeout) throws Exception;
    }

    public static final class SubmissionUnknownException extends PrintException {
        public SubmissionUnknownException(String message) { super(message); }
    }

    private final CommandRunner runner;
    private final String pos58Queue;
    private final QueueValidator queueValidator;
    private final boolean manualTear;

    public MacRawPrinter() {
        this(MacRawPrinter::runProcess, System.getProperty(POS58_QUEUE_PROPERTY, ""), MacRawPrinter::requirePos58Driver,
                Boolean.getBoolean(POS58_MANUAL_TEAR_PROPERTY));
    }
    MacRawPrinter(CommandRunner runner) { this(runner, "", MacRawPrinter::requirePos58Driver); }
    MacRawPrinter(CommandRunner runner, String pos58Queue, QueueValidator queueValidator) {
        this(runner, pos58Queue, queueValidator, false);
    }
    MacRawPrinter(CommandRunner runner, String pos58Queue, QueueValidator queueValidator, boolean manualTear) {
        this.runner = runner;
        this.pos58Queue = pos58Queue;
        this.queueValidator = queueValidator;
        this.manualTear = manualTear;
    }

    public void submit(PrintService printer, byte[] bytes) throws Exception {
        String destination = destination(printer);
        MacPos58RasterDocument.Document document = null;
        if (destination.equals(pos58Queue) && MacPos58RasterDocument.matches(bytes)) {
            queueValidator.validate(destination);
            document = MacPos58RasterDocument.prepare(bytes);
        }
        Path spool = Files.createTempFile("tea-print-", document == null ? ".bin" : ".pdf");
        try {
            Files.write(spool, document == null ? bytes : document.pdf());
            List<String> command = new ArrayList<>(List.of("/usr/bin/lpr", "-H", "localhost:631", "-P", destination));
            if (document == null) {
                command.addAll(List.of("-o", "raw", "-o", "job-sheets=none"));
            } else {
                command.addAll(List.of("-#", "1", "-o", "job-sheets=none", "-o", "media=" + document.media(),
                        "-o", "PageCutType=0NoCutPage", "-o",
                        "DocCutType=" + (document.partialCut() && !manualTear ? "1PartialCutDoc" : "0NoCutDoc"),
                        "-o", "FeedCutAfterJobEnd=0None", "-o", "Brightness=0", "-o", "HalftoneType=None"));
            }
            command.add(spool.toString());
            int exit = runner.run(command, Duration.ofSeconds(20));
            // Once a submitter starts, a failure may still follow acceptance by CUPS.
            // Never automatically retry via AUTOSENSE or release this job's replay guard.
            if (exit != 0) throw new SubmissionUnknownException(
                    "Mac打印提交未正常结束（exit=" + exit + "），请核对打印队列和纸张后处理，勿自动重打");
            if (document == null) {
                log.info("Mac RAW submitted: queue={}, bytes={}, exit=0; physical output not confirmed", destination, bytes.length);
            } else {
                log.info("Mac POS-58 raster PDF submitted: queue={}, sourceBytes={}, exit=0; physical output not confirmed",
                        destination, bytes.length);
            }
        } finally {
            try { Files.deleteIfExists(spool); }
            catch (IOException e) { log.warn("Could not remove task-owned print spool file"); }
        }
    }

    static void requirePos58Driver(String destination) throws Exception {
        String ppd = Files.readString(Path.of("/etc/cups/ppd", destination + ".ppd"));
        if (!ppd.contains("rastertosnailep-pos") || !ppd.contains("/HWResolution[203 203]")
                || !java.util.regex.Pattern.compile("(?m)^\\*MaxMediaWidth:\\s*\"136(?:\\.0+)?\"").matcher(ppd).find()) {
            throw new PrintException("所选队列未使用已验证的203dpi POS-58驱动；尚未提交打印");
        }
        byte[] driver = Files.readAllBytes(Path.of("/usr/libexec/cups/filter/rastertosnailep-pos"));
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(driver));
        if (!POS58_DRIVER_SHA256.equals(fingerprint))
            throw new PrintException("POS-58驱动版本未经此兼容配置验证；尚未提交打印，未自动切换RAW");
    }

    static String destination(PrintService printer) throws PrintException {
        PrinterURI attribute = printer.getAttribute(PrinterURI.class);
        URI uri = attribute == null ? null : attribute.getURI();
        if (uri == null || !Set.of("ipp", "ipps", "http", "https").contains(uri.getScheme())
                || uri.getHost() == null
                || !Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 631)) {
            throw new PrintException("无法确认本机CUPS打印队列，请在Mac系统中添加打印机后重新选择映射");
        }
        String path = uri.getPath();
        String prefix = path != null && path.startsWith("/classes/") ? "/classes/" : "/printers/";
        if (path == null || !path.startsWith(prefix)) throw new PrintException("无效的本机CUPS打印队列地址");
        String queue = path.substring(prefix.length());
        if (!queue.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) throw new PrintException("无法安全识别本机CUPS打印队列名称");
        return queue;
    }

    static int runProcess(List<String> command, Duration timeout) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        // Match the local CUPS endpoint used to resolve PrinterURI; inherited CLI routing must not redirect it.
        builder.environment().remove("CUPS_SERVER");
        builder.environment().remove("IPP_PORT");
        Process process = builder.start(); // Failure here means no submitter was started.
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new SubmissionUnknownException("Mac打印提交超时，结果待核对，勿自动重打");
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SubmissionUnknownException("Mac打印提交被中断，结果待核对，勿自动重打");
        } catch (IOException e) {
            throw new SubmissionUnknownException("Mac打印提交状态读取失败，结果待核对，勿自动重打");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
