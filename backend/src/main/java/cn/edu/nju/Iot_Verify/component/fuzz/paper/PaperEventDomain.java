package cn.edu.nju.Iot_Verify.component.fuzz.paper;

import java.util.List;

/** Model-independent source of legal paper-event choices. */
public interface PaperEventDomain {

    int MAX_TRACE_LENGTH = 10_000;
    int MAX_TARGETS_PER_TYPE = 1_000;
    int MAX_VALUES_PER_TARGET = 1_000;

    int traceLength();

    List<PaperDeviceDomain> deviceDomains();

    List<PaperEnvironmentDomain> environmentDomains();

    default List<PaperInitialValueDomain> localVariableDomains() {
        return List.of();
    }

    default List<PaperInitialValue> materializeInitialValues(long initializationNonce) {
        return PaperEventDomainSnapshot.copyOf(this)
                .materializeInitialValues(initializationNonce);
    }

}
