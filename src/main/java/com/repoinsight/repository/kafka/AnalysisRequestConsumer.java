
package com.repoinsight.repository.kafka;

import com.repoinsight.repository.service.AnalysisService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class AnalysisRequestConsumer {

    private final AnalysisService analysisService;

    public AnalysisRequestConsumer(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @KafkaListener(
            topics = "analysis-requests",
            groupId = "repoinsight-group"
    )
    public void consumeAnalysisRequest(String message) {
        Long reportId = Long.valueOf(message);

        System.out.println(
                "Received analysis request for report ID: " + reportId);

        analysisService.analyzeReport(reportId);
    }
}
