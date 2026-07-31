package tigerworkshop.webapphardwarebridge.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PrinterMappingDTO {
    private String type;
    private String printerName;
    private boolean available;
}
