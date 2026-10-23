package com.repoinsight.repository.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class AnalysisRequestProducer {

    private static final String TOPIC = "analysis-requests";
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafkaTemplate;

    public AnalysisRequestProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Wait for Kafka's acknowledgement so callers can distinguish a published
     * request from a failed/timed-out publish.
     */
    public void sendAnalysisRequest(Long reportId) {
        if (reportId == null || reportId <= 0) {
            throw new IllegalArgumentException("reportId must be a positive number");
        }

        try {
            SendResult<String, String> result = kafkaTemplate
                    .send(TOPIC, reportId.toString())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            System.out.printf(
                    "Published analysis request: reportId=%d, topic=%s, partition=%d, offset=%d%n",
                    reportId,
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while publishing analysis request for report " + reportId, ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException(
                    "Could not publish analysis request for report " + reportId, ex);
        }
    }
}
