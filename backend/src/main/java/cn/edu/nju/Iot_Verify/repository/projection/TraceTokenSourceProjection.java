package cn.edu.nju.Iot_Verify.repository.projection;

/** Scalar token provenance from the first frozen state, without loading trajectory evidence. */
public interface TraceTokenSourceProjection {
    Long getId();
    String getDeviceIdsJson();
    String getTokenSourcesJson();
}
