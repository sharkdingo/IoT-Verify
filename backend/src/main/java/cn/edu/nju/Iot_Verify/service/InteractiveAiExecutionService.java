package cn.edu.nju.Iot_Verify.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/** Bounded, user-cancellable execution for synchronous AI recommendations. */
@Service
public class InteractiveAiExecutionService extends InteractiveExecutionService {

    @Autowired
    public InteractiveAiExecutionService(
            @Qualifier("interactiveAiExecutor") ThreadPoolTaskExecutor executor,
            DistributedInteractiveExecutionStore distributedStore) {
        super(Kind.RECOMMENDATION, executor, distributedStore);
    }

    @Override
    @Scheduled(fixedDelay = 1000L)
    public void pollDistributedCancellation() {
        super.pollDistributedCancellation();
    }
}
