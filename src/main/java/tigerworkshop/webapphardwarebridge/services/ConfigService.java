package tigerworkshop.webapphardwarebridge.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import tigerworkshop.webapphardwarebridge.dtos.Config;

import java.io.File;
import java.io.IOException;

@Log4j2
public class ConfigService {
    @Getter
    private static final ConfigService instance = new ConfigService();

    private static final String CONFIG_FILENAME = "config.json";
    private static final String PRINTER_PLACEHOLDER = "";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Getter
    private Config config = new Config();

    private ConfigService() {
        File configFile = new File(CONFIG_FILENAME);
        try {
            loadFromFile(CONFIG_FILENAME);
        } catch (Exception e) {
            if (!configFile.exists()) {
                log.warn("Config file does not exist, creating a default file");
                save();
            } else {
                log.error("Failed loading config; keeping the invalid file intact and using defaults", e);
            }
        }
    }

    public void loadFromJson(String json) throws JsonProcessingException {
        log.info("Loading config from JSON");
        Config candidate = objectMapper.readValue(json, Config.class);
        PrinterMappingService.normalizeAndValidate(candidate);
        config = candidate;
    }

    public void loadFromFile(String filename) throws IOException {
        log.info("Loading config from file: {}", filename);
        Config candidate = objectMapper.readValue(new File(filename), Config.class);
        PrinterMappingService.normalizeAndValidate(candidate);
        config = candidate;
    }

    public void save() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(CONFIG_FILENAME), config);
        } catch (Exception e) {
            log.error("Failed to save config file", e);
        }
    }

    public void addPrintTypeToList(String printType) {
        String normalizedType = PrinterMappingService.normalizeType(printType);
        if (PrinterMappingService.findMapping(config, normalizedType).isPresent()) {
            return;
        }
        config.getPrinter().getMappings()
                .add(new Config.PrinterMapping(normalizedType, PRINTER_PLACEHOLDER, false, true, 0));
        PrinterMappingService.normalizeAndValidate(config);
        save();
    }
}
