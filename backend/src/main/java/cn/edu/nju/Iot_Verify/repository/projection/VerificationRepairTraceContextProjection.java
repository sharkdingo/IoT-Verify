package cn.edu.nju.Iot_Verify.repository.projection;

/** Automatic repair also needs the frozen templates used to produce the trace. */
public interface VerificationRepairTraceContextProjection extends VerificationTraceContextProjection {
    String getTemplateSnapshotsJson();
}
