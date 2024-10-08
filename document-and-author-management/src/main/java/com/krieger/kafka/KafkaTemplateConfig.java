package com.krieger.kafka;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.TopicBuilder;

import java.util.Objects;

@Configuration
@RequiredArgsConstructor
public class KafkaTemplateConfig {

    private final Environment environment;

    @Bean
    public NewTopic newTopic() {
        return TopicBuilder
                .name(Objects.requireNonNull(environment.getProperty("kafka.topic")))
                .build();
    }

}
