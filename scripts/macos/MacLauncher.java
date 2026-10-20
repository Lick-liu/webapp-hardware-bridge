package cn.teahouses.printhelper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Desktop;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.swing.JOptionPane;

/** A native app must keep writable data outside its signed/read-only bundle. */
public final class MacLauncher {
    public static void main(String[] args) {
        boolean smoke = Arrays.asList(args).contains("--smoke-test");
        try {
            run(smoke);
        } catch (Exception e) {
            e.printStackTrace();
            if (!smoke) {
                JOptionPane.showMessageDialog(null,
                        "打印助手启动失败：" + e.getMessage()
                        + "\n日志目录：~/Library/Application Support/TeaPrintHelper/log",
                        "茶坊村打印助手", JOptionPane.ERROR_MESSAGE);
            }
            System.exit(1);
        }
    }

    private static void run(boolean smoke) throws Exception {
        if (!System.getProperty("os.name").startsWith("Mac")) {
            throw new IllegalStateException("This launcher requires macOS");
        }
        Path data = smoke ? Files.createTempDirectory("tea-print-helper-smoke-")
                : Path.of(System.getProperty("user.home"), "Library", "Application Support", "TeaPrintHelper");
        Files.createDirectories(data.resolve("log"));
        JsonNode config = Files.exists(data.resolve("config.json"))
                ? new ObjectMapper().readTree(data.resolve("config.json").toFile())
                : new ObjectMapper().createObjectNode();
        int port = config.path("server").path("port").asInt(12212);
        boolean tls = config.path("server").path("tls").path("enabled").asBoolean(false);
        URI settings = URI.create((tls ? "https" : "http") + "://127.0.0.1:" + port + "/");
        try (FileChannel channel = FileChannel.open(data.resolve(".launcher.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) {
                if (!smoke) Desktop.getDesktop().browse(settings);
                return;
            }
            // Do not take over another helper or report another process as our readiness.
            try (ServerSocket unused = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
                unused.setReuseAddress(false);
            }
            String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                    .map(p -> Path.of(p).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            Process child = new ProcessBuilder(java.toString(), "-cp", classpath,
                    "tigerworkshop.webapphardwarebridge." + (smoke ? "Server" : "GUI"))
                    .directory(data.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(data.resolve("log/launcher-console.log").toFile()))
                    .start();
            Thread shutdown = new Thread(child::destroy, "print-helper-child-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
                boolean ready = false;
                while (System.nanoTime() < deadline && child.isAlive()) {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
                        ready = true;
                        break;
                    } catch (java.io.IOException e) {
                        Thread.sleep(250);
                    }
                }
                if (!ready || !child.isAlive()) throw new IllegalStateException("服务未启动，请检查日志或端口 " + port);
                if (smoke) {
                    for (String endpoint : new String[]{"", "config.json", "system/version.json", "system/printers.json"}) {
                        HttpURLConnection connection = (HttpURLConnection) settings.resolve(endpoint).toURL().openConnection();
                        connection.setConnectTimeout(5000);
                        connection.setReadTimeout(15000);
                        try {
                            if (connection.getResponseCode() != 200) throw new IllegalStateException("HTTP probe failed: " + endpoint);
                            try (var in = connection.getInputStream()) { in.readAllBytes(); }
                        } finally { connection.disconnect(); }
                    }
                    if (!Files.isRegularFile(data.resolve("config.json"))) throw new IllegalStateException("User config missing");
                    System.out.println("MAC_APP_SMOKE_PASS data=" + data);
                } else {
                    Desktop.getDesktop().browse(settings);
                    int exit = child.waitFor();
                    if (exit != 0) throw new IllegalStateException("打印助手异常退出：" + exit);
                }
            } finally {
                // Only terminate the child this launcher created, never another user's process.
                child.destroy();
                if (!child.waitFor(10, TimeUnit.SECONDS)) child.destroyForcibly();
                Runtime.getRuntime().removeShutdownHook(shutdown);
            }
        }
    }
}
