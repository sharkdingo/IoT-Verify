package cn.edu.nju.Iot_Verify.component.nusmv.generator.data;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.PropertyDimension;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;

/** Resolves the step-zero label of an emitted mode state. */
public final class ModeStateInitialLabels {

    private ModeStateInitialLabels() {}

    public static String resolve(DeviceSmvData smv, String mode, String state, PropertyDimension dimension) {
        int modeIndex = smv.getModes().indexOf(mode);
        boolean trust = dimension == PropertyDimension.TRUST;
        if (state.equals(currentModeState(smv, mode, modeIndex))) {
            String instanceLabel = trust ? smv.getInstanceStateTrust() : smv.getInstanceStatePrivacy();
            if (instanceLabel != null) return DeviceSmvDataFactory.normalizeTrustPrivacy(instanceLabel);
        }
        if (trust) {
            String extractedTrust = smv.getModeStateTrust().get(mode + "_" + state);
            if (extractedTrust != null) return DeviceSmvDataFactory.normalizeTrustPrivacy(extractedTrust);
        }
        String label = manifestLabel(smv.getManifest(), modeIndex, state, trust);
        String defaultLabel = trust ? dimension.activeValue : dimension.inactiveValue;
        return DeviceSmvDataFactory.normalizeTrustPrivacy(label != null ? label : defaultLabel);
    }

    private static String currentModeState(DeviceSmvData smv, String mode, int modeIndex) {
        String explicit = smv.getCurrentModeStates().get(mode);
        if (explicit != null || smv.getCurrentState() == null) return explicit;
        String[] parts = smv.getCurrentState().split(";");
        return componentState(parts, modeIndex);
    }

    private static String manifestLabel(DeviceManifest manifest, int modeIndex, String state, boolean trust) {
        if (manifest == null || manifest.getWorkingStates() == null) return null;
        for (DeviceManifest.WorkingState workingState : manifest.getWorkingStates()) {
            String name = workingState.getName();
            if (name == null) continue;
            String candidate = name.contains(";")
                    ? componentState(name.split(";", -1), modeIndex)
                    : DeviceSmvDataFactory.cleanStateName(name);
            if (state.equals(candidate)) {
                return trust ? workingState.getTrust() : workingState.getPrivacy();
            }
        }
        return null;
    }

    private static String componentState(String[] parts, int index) {
        if (index < 0 || index >= parts.length || parts[index].isBlank()) return null;
        return DeviceSmvDataFactory.cleanStateName(parts[index].trim());
    }
}
