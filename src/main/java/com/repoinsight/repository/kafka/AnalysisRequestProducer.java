
package com.repoinsight.repository.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class AnalysisRequestProducer {

    private static final String TOPIC = "analysis-requests";

    private final KafkaTemplate<String, String> kafkaTemplate;

    public AnalysisRequestProducer(
            KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendAnalysisRequest(Long reportId) {
        kafkaTemplate.send(TOPIC, String.valueOf(reportId));
        System.out.println(
                "Sent analysis request for report ID: " + reportId);
    }
}
