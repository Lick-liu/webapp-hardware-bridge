package tigerworkshop.webapphardwarebridge.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocalPrintDeviceContextDTO {
    private String deviceId;
    private String shopId;
    private Long activationTaskId;
    private String activationToken;
}
