package tigerworkshop.webapphardwarebridge.services;

import lombok.extern.log4j.Log4j2;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.attribute.standard.PrinterURI;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** ESC/POS bytes must bypass CUPS document filters, even on a non-raw system queue. */
@Log4j2
public final class MacRawPrinter {
    @FunctionalInterface
    interface CommandRunner {
        int run(List<String> command, Duration timeout) throws Exception;
    }

    public static final class SubmissionUnknownException extends PrintException {
        public SubmissionUnknownException(String message) { super(message); }
    }

    private final CommandRunner runner;

    public MacRawPrinter() { this(MacRawPrinter::runProcess); }
    MacRawPrinter(CommandRunner runner) { this.runner = runner; }

    public void submit(PrintService printer, byte[] bytes) throws Exception {
        String destination = destination(printer);
        Path spool = Files.createTempFile("tea-print-raw-", ".bin");
        try {
            Files.write(spool, bytes);
            int exit = runner.run(List.of("/usr/bin/lpr", "-H", "localhost:631", "-P", destination,
                    "-o", "raw", "-o", "job-sheets=none", spool.toString()), Duration.ofSeconds(20));
            // Once a submitter starts, a failure may still follow acceptance by CUPS.
            // Never automatically retry via AUTOSENSE or release this job's replay guard.
            if (exit != 0) throw new SubmissionUnknownException(
                    "Mac打印提交未正常结束（exit=" + exit + "），请核对打印队列和纸张后处理，勿自动重打");
            log.info("Mac RAW submitted: queue={}, bytes={}, exit=0; physical output not confirmed", destination, bytes.length);
        } finally {
            try { Files.deleteIfExists(spool); }
            catch (IOException e) { log.warn("Could not remove task-owned raw spool file"); }
        }
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
