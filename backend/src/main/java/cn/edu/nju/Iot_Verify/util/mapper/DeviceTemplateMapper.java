package cn.edu.nju.Iot_Verify.util.mapper;

import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Component;

@Component
public class DeviceTemplateMapper {

    public DeviceTemplateDto toDto(DeviceTemplatePo po) {
        if (po == null) {
            return null;
        }
        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setId(po.getId());
        dto.setName(po.getName());
        if (po.getDefaultTemplate() == null) {
            throw new cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException(
                    "device template", po.getId(), "defaultTemplate",
                    "template provenance must be explicitly bundled or custom");
        }
        dto.setDefaultTemplate(po.getDefaultTemplate());

        DeviceManifest manifest = JsonUtils.readPersistedJsonRequired(
                "device template", po.getId(), "manifestJson", po.getManifestJson(),
                () -> JsonUtils.fromJson(
                        po.getManifestJson(), new TypeReference<DeviceManifest>() {}));
        if (po.getName() == null || manifest.getName() == null
                || !po.getName().equals(manifest.getName())) {
            throw new cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException(
                    "device template", po.getId(), "manifestJson",
                    "database name and manifest.Name must be exactly equal");
        }
        dto.setManifest(manifest);
        return dto;
    }
}
