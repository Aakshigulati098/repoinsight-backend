package com.repoinsight.repository.config;

import java.time.Duration;

import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAiTimeoutConfig {

    @Bean
    OpenAiHttpClientBuilderCustomizer openAiHttpClientBuilderCustomizer() {
        return builder -> builder.timeout(Duration.ofSeconds(6));
    }
}
