package com.repoinsight.repository.kafka;

import com.repoinsight.repository.service.AnalysisService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AnalysisRequestConsumerTest {

    private final AnalysisService analysisService = mock(AnalysisService.class);
    private final AnalysisRequestConsumer consumer = new AnalysisRequestConsumer(analysisService);

    @Test
    void validMessageTriggersAnalysisForReportId() {
        consumer.consumeAnalysisRequest("42");
        verify(analysisService).analyzeReport(42L);
    }

    @Test
    void malformedMessageThrowsIllegalArgumentException() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> consumer.consumeAnalysisRequest("not-a-number"));
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                NumberFormatException.class, exception.getCause());
        verifyNoInteractions(analysisService);
    }

    @Test
    void blankMessageThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> consumer.consumeAnalysisRequest(" "));
        verifyNoInteractions(analysisService);
    }
}
