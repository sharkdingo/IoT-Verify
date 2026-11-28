package cn.edu.nju.Iot_Verify.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/** Request-scoped cancellation for bounded automatic-fix searches. */
@Service
public class InteractiveFixExecutionService extends InteractiveExecutionService {

    @Autowired
    public InteractiveFixExecutionService(
            @Qualifier("syncVerificationExecutor") ThreadPoolTaskExecutor executor,
            DistributedInteractiveExecutionStore distributedStore) {
        super(Kind.FIX, executor, distributedStore);
    }

    @Override
    @Scheduled(fixedDelay = 1000L)
    public void pollDistributedCancellation() {
        super.pollDistributedCancellation();
    }
}
