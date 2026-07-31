package tigerworkshop.webapphardwarebridge.services;

import tigerworkshop.webapphardwarebridge.dtos.Config;
import tigerworkshop.webapphardwarebridge.dtos.PrinterMappingDTO;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public final class PrinterMappingService {
    private static final int MAX_TYPE_LENGTH = 64;

    private PrinterMappingService() {
    }

    public static void normalizeAndValidate(Config config) {
        if (config == null) {
            throw new IllegalArgumentException("Config is required");
        }
        if (config.getPrinter() == null) {
            config.setPrinter(new Config.Printer());
        }
        if (config.getPrinter().getMappings() == null) {
            config.getPrinter().setMappings(new ArrayList<>());
        }

        Set<String> types = new HashSet<>();
        for (Config.PrinterMapping mapping : config.getPrinter().getMappings()) {
            if (mapping == null) {
                throw new IllegalArgumentException("Printer mapping cannot be null");
            }
            String normalizedType = normalizeType(mapping.getType());
            if (!types.add(normalizedType)) {
                throw new IllegalArgumentException("Duplicate printer mapping type: " + normalizedType);
            }
            mapping.setType(normalizedType);
        }
    }

    public static String normalizeType(String type) {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Printer mapping type cannot be blank");
        }
        String normalized = type.trim();
        if (normalized.length() > MAX_TYPE_LENGTH) {
            throw new IllegalArgumentException("Printer mapping type cannot exceed 64 characters");
        }
        return normalized;
    }

    public static Optional<Config.PrinterMapping> findMapping(Config config, String type) {
        if (config == null || config.getPrinter() == null || config.getPrinter().getMappings() == null) {
            return Optional.empty();
        }
        final String normalizedType;
        try {
            normalizedType = normalizeType(type);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        return config.getPrinter().getMappings().stream()
                .filter(mapping -> normalizedType.equals(mapping.getType()))
                .findFirst();
    }

    public static List<PrinterMappingDTO> listSafeMappings(
            Config config, PrintServiceDiscoveryService discoveryService) {
        return listSafeMappings(config,
                printerName -> discoveryService.findPrintServiceByName(printerName).isPresent());
    }

    static List<PrinterMappingDTO> listSafeMappings(Config config, Predicate<String> availability) {
        List<PrinterMappingDTO> result = new ArrayList<>();
        if (config == null || config.getPrinter() == null || config.getPrinter().getMappings() == null) {
            return result;
        }
        for (Config.PrinterMapping mapping : config.getPrinter().getMappings()) {
            String printerName = mapping.getName();
            boolean available = printerName != null
                    && !printerName.trim().isEmpty()
                    && availability.test(printerName);
            result.add(new PrinterMappingDTO(mapping.getType(), printerName, available));
        }
        return result;
    }
}
