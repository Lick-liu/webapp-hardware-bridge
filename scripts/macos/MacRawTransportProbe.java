package cn.teahouses.printhelper;

import com.fasterxml.jackson.databind.ObjectMapper;
import tigerworkshop.webapphardwarebridge.interfaces.WebSocketServerInterface;
import tigerworkshop.webapphardwarebridge.interfaces.WebSocketServiceInterface;
import tigerworkshop.webapphardwarebridge.responses.PrintDocument;
import tigerworkshop.webapphardwarebridge.websocketservices.PrinterWebSocketService;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.attribute.standard.PrinterURI;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** CI-only: actual packaged helper -> local CUPS PostScript queue -> loopback byte sink. */
public final class MacRawTransportProbe {
    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS"))) throw new IllegalStateException("CI only");
        String queue = "TeaRawProbe" + System.nanoTime();
        String models = command("/usr/sbin/lpinfo", "-m");
        String model = models.lines().filter(l -> l.toLowerCase().contains("generic") && l.toLowerCase().contains("postscript"))
                .map(l -> l.split("\\s+", 2)[0]).findFirst().orElseThrow(() -> new IllegalStateException("No generic PostScript test driver"));
        try (ServerSocket sink = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            sink.setSoTimeout(45_000);
            command("sudo", "-n", "/usr/sbin/lpadmin", "-p", queue, "-D", "Tea Raw Probe Display Name", "-E",
                    "-v", "socket://127.0.0.1:" + sink.getLocalPort(), "-m", model);
            PrintService printer = Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                    .filter(p -> { PrinterURI uri = p.getAttribute(PrinterURI.class);
                        return uri != null && uri.getURI().getPath().endsWith("/" + queue); })
                    .findFirst().orElseThrow(() -> new IllegalStateException("CUPS test queue not discovered"));
            System.out.println("RAW_PROBE queue=" + queue + " uri=" + printer.getAttribute(PrinterURI.class) + " model=" + model);
            ObjectMapper mapper = new ObjectMapper();
            Files.writeString(Path.of("config.json"), mapper.writeValueAsString(Map.of("printer", Map.of(
                    "fallbackToDefault", false, "mappings", new Object[]{Map.of("type", "RAW_CI", "name", printer.getName())}))));
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.write(new byte[]{27, 64, 27, 97, 0, 29, 118, 48, 0, 48, 0, 45, 0});
            for (int i = 0; i < 48 * 45; i++) payload.write(i & 255);
            payload.write(new byte[]{10, (byte)0xd6, (byte)0xd0, (byte)0xce, (byte)0xc4, 10});
            byte[] expected = payload.toByteArray();
            CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> {
                try (var connection = sink.accept(); var input = connection.getInputStream()) {
                    connection.setSoTimeout(4000);
                    ByteArrayOutputStream data = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    try { int n; while ((n = input.read(buffer)) >= 0) data.write(buffer, 0, n); }
                    catch (SocketTimeoutException end) { /* CUPS can wait for a printer's EOF. */ }
                    return data.toByteArray();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            PrinterWebSocketService service = new PrinterWebSocketService();
            String[] result = new String[1];
            service.onRegister(new WebSocketServerInterface() {
                public void messageToServer(String channel, String message) { result[0] = message; }
                public void messageToServer(String channel, byte[] message) { }
                public void messageToService(String channel, String message) { }
                public void messageToService(String channel, byte[] message) { }
                public void registerService(WebSocketServiceInterface other) { }
                public void unregisterService(WebSocketServiceInterface other) { }
            });
            PrintDocument document = mapper.readValue(mapper.writeValueAsString(Map.of("id", "raw-ci-1", "type", "RAW_CI",
                    "raw_content", java.util.Base64.getEncoder().encodeToString(expected))), PrintDocument.class);
            service.printDocument(document);
            if (result[0] == null || !"SUCCESS".equals(mapper.readTree(result[0]).path("state").asText()))
                throw new IllegalStateException("RAW production dispatch failed: " + result[0]);
            byte[] actual = received.get(50, TimeUnit.SECONDS);
            if (!Arrays.equals(expected, actual)) throw new IllegalStateException("CUPS changed bytes: expected=" + expected.length + " actual=" + actual.length);
            System.out.println("MAC_CUPS_RAW_BYTES_PASS bytes=" + actual.length + " productionDispatch=true genericPostScriptQueue=true");
        }
        // The uniquely named loopback-only queue lives only in this disposable CI runner.
    }

    private static String command(String... args) throws Exception {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        var output = CompletableFuture.supplyAsync(() -> {
            try { return new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        if (!p.waitFor(45, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IllegalStateException("CUPS setup timed out"); }
        String text = output.get(5, TimeUnit.SECONDS);
        if (p.exitValue() != 0) throw new IllegalStateException("CUPS setup failed: " + text);
        return text;
    }
}
