package com.repoinsight.repository.kafka;

import com.repoinsight.repository.service.AnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class AnalysisRequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRequestConsumer.class);

    private final AnalysisService analysisService;

    public AnalysisRequestConsumer(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @KafkaListener(topics = "analysis-requests", groupId = "repoinsight-group")
    public void consumeAnalysisRequest(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Received an empty analysis request message");
        }

        final long reportId;
        try {
            reportId = Long.parseLong(message.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "Analysis request must contain a numeric report ID; received: " + message, ex);
        }

        if (reportId <= 0) {
            throw new IllegalArgumentException("Analysis report ID must be positive: " + reportId);
        }

        log.info("Received analysis request for report ID {}", reportId);
        // Let processing exceptions propagate so Spring Kafka's error handler
        // can apply its retry/recovery policy instead of acknowledging
        // a message whose processing failed.
        analysisService.analyzeReport(reportId);
    }
}
