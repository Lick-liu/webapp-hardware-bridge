package tigerworkshop.webapphardwarebridge.services;

import org.junit.Test;
import tigerworkshop.webapphardwarebridge.dtos.Config;
import tigerworkshop.webapphardwarebridge.dtos.PrinterMappingDTO;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PrinterMappingServiceTest {
    @Test
    public void rejectsBlankMappingType() {
        Config config = configWithMappings(mapping(" ", "Front Printer"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PrinterMappingService.normalizeAndValidate(config)
        );

        assertTrue(exception.getMessage().contains("blank"));
    }

    @Test
    public void rejectsDuplicateMappingTypeAfterTrimming() {
        Config config = configWithMappings(
                mapping("KITCHEN_A", "Kitchen Printer 1"),
                mapping(" KITCHEN_A ", "Kitchen Printer 2")
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> PrinterMappingService.normalizeAndValidate(config)
        );

        assertTrue(exception.getMessage().contains("Duplicate"));
    }

    @Test
    public void resolvesEachTypeToItsOwnPhysicalPrinter() {
        Config config = configWithMappings(
                mapping("RECEIPT", "Front Printer"),
                mapping("KITCHEN_A", "Kitchen Printer")
        );
        PrinterMappingService.normalizeAndValidate(config);

        assertEquals("Front Printer",
                PrinterMappingService.findMapping(config, "RECEIPT").orElseThrow().getName());
        assertEquals("Kitchen Printer",
                PrinterMappingService.findMapping(config, "KITCHEN_A").orElseThrow().getName());
        assertFalse(PrinterMappingService.findMapping(config, "UNKNOWN").isPresent());
    }

    @Test
    public void safeMappingViewContainsOnlyDisplayFieldsAndAvailability() {
        Config config = configWithMappings(
                mapping("FRONT", "Front Printer"),
                mapping("KITCHEN_A", "Kitchen Printer")
        );
        PrinterMappingService.normalizeAndValidate(config);

        List<PrinterMappingDTO> mappings =
                PrinterMappingService.listSafeMappings(config, "Kitchen Printer"::equals);

        assertEquals(Arrays.asList("FRONT", "KITCHEN_A"),
                mappings.stream().map(PrinterMappingDTO::getType).toList());
        assertEquals(Arrays.asList("Front Printer", "Kitchen Printer"),
                mappings.stream().map(PrinterMappingDTO::getPrinterName).toList());
        assertFalse(mappings.get(0).isAvailable());
        assertTrue(mappings.get(1).isAvailable());
    }

    private Config configWithMappings(Config.PrinterMapping... mappings) {
        Config config = new Config();
        config.getPrinter().setMappings(new ArrayList<>(List.of(mappings)));
        return config;
    }

    private Config.PrinterMapping mapping(String type, String name) {
        return new Config.PrinterMapping(type, name, false, true, 0);
    }
}
