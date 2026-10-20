package tigerworkshop.webapphardwarebridge.dtos;

import lombok.Data;

@Data
public class LocalPrintDeviceActivationDTO {
    private String shopId;
    private Long activationTaskId;
    private String activationToken;
}
