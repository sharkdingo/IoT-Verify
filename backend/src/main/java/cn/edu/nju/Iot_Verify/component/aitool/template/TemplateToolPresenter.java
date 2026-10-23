package cn.edu.nju.Iot_Verify.component.aitool.template;

import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Full assistant manifests share one projection; only the UI-only icon is omitted. */
public final class TemplateToolPresenter {
    public static final String SEMANTIC_NOTE = "Interpret InternalVariables by IsInside, not the collection name: true is device-local; "
            + "false is one shared environment value per name across the scene. Reads=false grants no reading; "
            + "ImpactedVariables lists shared effect targets; Dynamics for those names describe effects on that shared value, not a private copy. "
            + "Shared numeric evolution combines all active device effects with NaturalChangeRate and clamps to the domain. "
            + "Reads=true mirrors the shared value outside compromise modeling; only an enabled compromise of a "
            + "FalsifiableWhenCompromised reading may separate its report from the environment. Distinguish those specification "
            + "subjects without inventing independent physical values. Shared declaration Trust/Privacy are defaults: current shared "
            + "labels come from the Board Environment Pool, with an untrusted per-device exception only under that compromise. "
            + "For standard Board formal checks, a saved device instance state overrides the template InitState; "
            + "InitState is only a default when that instance state is absent, not a separate formal initial state. "
            + "Interpret historical runs from their frozen modelSnapshot and actual trace, not the live Board. "
            + "These are model semantics, not observations of physical devices or proof that an action occurred.";

    private TemplateToolPresenter() { }

    public static Map<String, Object> present(DeviceTemplateDto template, ObjectMapper mapper) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", template.getId());
        detail.put("name", template.getName());
        detail.put("defaultTemplate", template.getDefaultTemplate());
        if (template.getManifest() != null) {
            Map<String, Object> manifest = mapper.convertValue(template.getManifest(),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
            manifest.remove("Icon");
            detail.put("manifest", manifest);
        }
        return detail;
    }
}
