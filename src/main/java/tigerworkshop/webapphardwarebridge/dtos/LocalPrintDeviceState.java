package tigerworkshop.webapphardwarebridge.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocalPrintDeviceState {
    private String deviceId;
    private Map<String, Long> shopActivationTaskIds = new LinkedHashMap<>();
}
