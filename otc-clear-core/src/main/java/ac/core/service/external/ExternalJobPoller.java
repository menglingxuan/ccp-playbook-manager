package ac.core.service.external;

import ac.core.config.AppMode;
import ac.core.config.AppProperties;
import ac.core.persistence.ExternalJobRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls active external jobs. One instance serves exactly one tenant, so no context switching. */
@Slf4j
@Component
public class ExternalJobPoller {

    private final ExternalJobService jobService;
    private final AppProperties properties;

    public ExternalJobPoller(ExternalJobService jobService, AppProperties properties) {
        this.jobService = jobService;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 5_000L, initialDelay = 15_000L)
    public void poll() {
        if (!properties.getExternal().isEnabled()) {
            return;
        }
        String tenantId = properties.getMode() == AppMode.TENANT
                ? properties.getTenant().getId() : "default";
        for (ExternalJobRow job : jobService.activeJobs(tenantId)) {
            try {
                if (jobService.timedOut(job)) {
                    jobService.markUnknown(tenantId, job.id(), "Polling timed out, refresh manually");
                    continue;
                }
                jobService.refreshStatus(tenantId, job);
            } catch (RuntimeException e) {
                log.warn("Polling job {} failed: {}", job.id(), e.getMessage());
            }
        }
    }
}
