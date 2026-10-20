package cn.edu.nju.Iot_Verify.repository.projection;

/** Frozen specification context only; no manifests, rules, or trajectory evidence. */
public interface FuzzSpecificationPresentationProjection {
    Long getId();
    String getSpecificationsJson();
    String getModelTokenSourcesJson();
}
