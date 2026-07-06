package tigerworkshop.webapphardwarebridge.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import tigerworkshop.webapphardwarebridge.dtos.PrintServiceDTO;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import java.awt.print.PrinterJob;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Log4j2
public class PrintServiceDiscoveryService {
    private static final int WINDOWS_LOOKUP_TIMEOUT_SECONDS = 10;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final PrintServiceDiscoveryService instance = new PrintServiceDiscoveryService(
            PrinterJob::lookupPrintServices,
            () -> PrintServiceLookup.lookupPrintServices(null, null),
            Arrays.asList(
                    new PowerShellWindowsPrinterLookup(),
                    new PowerShellGetPrinterLookup(),
                    new PowerShellRegistryPrinterLookup()
            ),
            () -> System.getProperty("os.name", "")
    );

    private final PrintServiceLookupSource printerJobLookupSource;
    private final PrintServiceLookupSource printServiceLookupSource;
    private final List<WindowsPrinterLookupSource> windowsPrinterLookupSources;
    private final Supplier<String> osNameSupplier;

    public static PrintServiceDiscoveryService getInstance() {
        return instance;
    }

    PrintServiceDiscoveryService(
            PrintServiceLookupSource printerJobLookupSource,
            PrintServiceLookupSource printServiceLookupSource,
            WindowsPrinterLookupSource windowsPrinterLookupSource,
            Supplier<String> osNameSupplier
    ) {
        this(printerJobLookupSource, printServiceLookupSource, Collections.singletonList(windowsPrinterLookupSource), osNameSupplier);
    }

    PrintServiceDiscoveryService(
            PrintServiceLookupSource printerJobLookupSource,
            PrintServiceLookupSource printServiceLookupSource,
            List<WindowsPrinterLookupSource> windowsPrinterLookupSources,
            Supplier<String> osNameSupplier
    ) {
        this.printerJobLookupSource = printerJobLookupSource;
        this.printServiceLookupSource = printServiceLookupSource;
        this.windowsPrinterLookupSources = new ArrayList<>(windowsPrinterLookupSources);
        this.osNameSupplier = osNameSupplier;
    }

    public List<PrintServiceDTO> listPrinters() {
        LinkedHashMap<String, PrintServiceDTO> printers = new LinkedHashMap<>();

        addJavaPrintServices(printers, printerJobLookupSource.lookup(), "Java PrinterJob");
        addJavaPrintServices(printers, printServiceLookupSource.lookup(), "Java PrintServiceLookup");

        if (isWindows()) {
            for (WindowsPrinterLookupSource windowsPrinterLookupSource : windowsPrinterLookupSources) {
                try {
                    addPrinterDTOs(printers, windowsPrinterLookupSource.lookup());
                } catch (Exception e) {
                    log.warn("Failed to list Windows printers from {}: {}", windowsPrinterLookupSource.getClass().getSimpleName(), e.getMessage());
                }
            }
        }

        return new ArrayList<>(printers.values());
    }

    public Optional<PrintService> findPrintServiceByName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }

        String expectedName = normalizedName(name);
        for (PrintService printService : lookupJavaPrintServices()) {
            if (printService != null && printService.getName() != null && normalizedName(printService.getName()).equals(expectedName)) {
                return Optional.of(printService);
            }
        }

        return Optional.empty();
    }

    private List<PrintService> lookupJavaPrintServices() {
        LinkedHashMap<String, PrintService> printServices = new LinkedHashMap<>();
        addPrintServices(printServices, printerJobLookupSource.lookup());
        addPrintServices(printServices, printServiceLookupSource.lookup());
        return new ArrayList<>(printServices.values());
    }

    private void addJavaPrintServices(LinkedHashMap<String, PrintServiceDTO> printers, PrintService[] printServices, String source) {
        if (printServices == null) {
            return;
        }

        for (PrintService printService : printServices) {
            if (printService == null) {
                continue;
            }

            addPrinterDTO(printers, new PrintServiceDTO(printService.getName(), source));
        }
    }

    private void addPrinterDTOs(LinkedHashMap<String, PrintServiceDTO> printers, List<PrintServiceDTO> printerDTOs) {
        if (printerDTOs == null) {
            return;
        }

        for (PrintServiceDTO printerDTO : printerDTOs) {
            addPrinterDTO(printers, printerDTO);
        }
    }

    private void addPrinterDTO(LinkedHashMap<String, PrintServiceDTO> printers, PrintServiceDTO printerDTO) {
        if (printerDTO == null || printerDTO.name == null || printerDTO.name.trim().isEmpty()) {
            return;
        }

        printers.putIfAbsent(normalizedName(printerDTO.name), printerDTO);
    }

    private void addPrintServices(LinkedHashMap<String, PrintService> printServicesByName, PrintService[] printServices) {
        if (printServices == null) {
            return;
        }

        for (PrintService printService : printServices) {
            if (printService == null || printService.getName() == null || printService.getName().trim().isEmpty()) {
                continue;
            }

            printServicesByName.putIfAbsent(normalizedName(printService.getName()), printService);
        }
    }

    private boolean isWindows() {
        return Optional.ofNullable(osNameSupplier.get()).orElse("").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String normalizedName(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    interface PrintServiceLookupSource {
        PrintService[] lookup();
    }

    @FunctionalInterface
    interface WindowsPrinterLookupSource {
        List<PrintServiceDTO> lookup() throws Exception;
    }

    private abstract static class PowerShellJsonPrinterLookup implements WindowsPrinterLookupSource {
        @Override
        public List<PrintServiceDTO> lookup() throws Exception {
            Process process = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-Command",
                    command()
            ).redirectErrorStream(true).start();

            boolean finished = process.waitFor(WINDOWS_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Timed out while querying Windows printers");
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new IOException("Windows printer query failed: " + output);
            }

            return parsePrinterJson(output);
        }

        protected abstract String command();

        private List<PrintServiceDTO> parsePrinterJson(String json) throws IOException {
            ArrayList<PrintServiceDTO> printers = new ArrayList<>();
            if (json == null || json.trim().isEmpty()) {
                return printers;
            }

            JsonNode root = objectMapper.readTree(json);
            if (root == null || root.isNull()) {
                return printers;
            }

            if (root.isArray()) {
                for (JsonNode printerNode : root) {
                    addPrinterFromJson(printers, printerNode);
                }
            } else {
                addPrinterFromJson(printers, root);
            }

            return printers;
        }

        private void addPrinterFromJson(ArrayList<PrintServiceDTO> printers, JsonNode printerNode) {
            String name = textValue(printerNode, "Name");
            if (name == null || name.trim().isEmpty()) {
                return;
            }

            printers.add(new PrintServiceDTO(name, description(printerNode)));
        }

        private String description(JsonNode printerNode) {
            StringJoiner description = new StringJoiner(", ");
            addDescriptionPart(description, "driver", textValue(printerNode, "DriverName"));
            addDescriptionPart(description, "port", textValue(printerNode, "PortName"));

            if (booleanValue(printerNode, "Default")) {
                description.add("default");
            }
            if (booleanValue(printerNode, "Network")) {
                description.add("network");
            }
            if (booleanValue(printerNode, "Shared")) {
                description.add("shared");
            }
            if (Optional.ofNullable(textValue(printerNode, "Name")).orElse("").toLowerCase(Locale.ROOT).contains("redirected")) {
                description.add("redirected");
            }

            return description.toString();
        }

        private void addDescriptionPart(StringJoiner description, String label, String value) {
            if (value != null && !value.trim().isEmpty()) {
                description.add(label + ": " + value);
            }
        }

        private String textValue(JsonNode printerNode, String fieldName) {
            JsonNode value = printerNode.get(fieldName);
            return value == null || value.isNull() ? null : value.asText();
        }

        private boolean booleanValue(JsonNode printerNode, String fieldName) {
            JsonNode value = printerNode.get(fieldName);
            return value != null && value.asBoolean(false);
        }
    }

    private static class PowerShellWindowsPrinterLookup extends PowerShellJsonPrinterLookup {
        @Override
        protected String command() {
            return "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; try { @(Get-CimInstance Win32_Printer -ErrorAction Stop | Select-Object Name,DriverName,PortName,Default,Network,Shared) | ConvertTo-Json -Compress } catch { @(Get-WmiObject Win32_Printer | Select-Object Name,DriverName,PortName,Default,Network,Shared) | ConvertTo-Json -Compress }";
        }
    }

    private static class PowerShellGetPrinterLookup extends PowerShellJsonPrinterLookup {
        @Override
        protected String command() {
            return "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; if (Get-Command Get-Printer -ErrorAction SilentlyContinue) { @(Get-Printer | Select-Object Name,DriverName,PortName) | ConvertTo-Json -Compress } else { @() | ConvertTo-Json -Compress }";
        }
    }

    private static class PowerShellRegistryPrinterLookup extends PowerShellJsonPrinterLookup {
        @Override
        protected String command() {
            return "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; $printers = @(); $devices = Get-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows NT\\CurrentVersion\\Devices' -ErrorAction SilentlyContinue; if ($devices) { $printers += $devices.PSObject.Properties | Where-Object { $_.Name -notlike 'PS*' } | ForEach-Object { $parts = ('' + $_.Value) -split ','; [pscustomobject]@{ Name = $_.Name; DriverName = $parts[0]; PortName = (($parts | Select-Object -Skip 1) -join ',') } } }; $printers += Get-ChildItem -Path 'HKLM:\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Print\\Printers' -ErrorAction SilentlyContinue | ForEach-Object { $item = Get-ItemProperty -LiteralPath $_.PSPath; [pscustomobject]@{ Name = $_.PSChildName; DriverName = $item.'Printer Driver'; PortName = $item.Port } }; @($printers) | ConvertTo-Json -Compress";
        }
    }
}
