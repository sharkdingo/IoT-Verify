package cn.edu.nju.Iot_Verify.dto.model;

/** Revision of the execution semantics shared by formal verification, simulation and exploration. */
public final class ModelSemanticsVersion {
    // Bump when the same input can produce different results or replay needs different evidence.
    public static final int CURRENT = 7;

    private ModelSemanticsVersion() {}

    public static boolean isCurrent(ModelRunSnapshotDto snapshot) {
        return snapshot != null && snapshot.getModelSemanticsVersion() == CURRENT;
    }
}
