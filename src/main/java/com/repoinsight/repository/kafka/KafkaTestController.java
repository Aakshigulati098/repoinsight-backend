package com.repoinsight.repository.kafka;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/kafka")
public class KafkaTestController {

    private final AnalysisRequestProducer producer;

    public KafkaTestController(AnalysisRequestProducer producer) {
        this.producer = producer;
    }

    @PostMapping("/test")
    public String testKafka(@RequestParam Long id) {
        producer.sendAnalysisRequest(id);
        return "Message sent to Kafka: " + id;
    }
}
