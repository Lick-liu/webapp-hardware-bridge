package tigerworkshop.webapphardwarebridge.responses;

import lombok.NoArgsConstructor;
import lombok.ToString;

@ToString
@NoArgsConstructor
public class PrintResult {
    public Boolean success;
    public String message;
    public String id;
    public String printerName;
    public String state;

    public PrintResult(Boolean success, String message, String id, String printerName) {
        this(success, message, id, printerName, Boolean.TRUE.equals(success) ? "SUCCESS" : "FAILED");
    }

    public PrintResult(Boolean success, String message, String id, String printerName, String state) {
        this.success = success;
        this.message = message;
        this.id = id;
        this.printerName = printerName;
        this.state = state;
    }
}
