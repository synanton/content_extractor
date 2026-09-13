package org.synanton.extraction.extraction.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.synanton.extraction.extraction.adapter.out.worker.ExtractionWorker;

@Component
public class WorkerLifecycle {

    private final ExtractionWorker extractionWorker;

    public WorkerLifecycle(ExtractionWorker extractionWorker) {
        this.extractionWorker = extractionWorker;
    }

    @PostConstruct
    public void startWorker() {
        extractionWorker.start();
    }
}
