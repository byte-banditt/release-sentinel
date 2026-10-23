package com.releasesentinel;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfiguration {
    @Bean NewTopic ciEvents() { return new NewTopic(KafkaTopics.EVENTS, 1, (short) 1); }
    @Bean NewTopic ciVerdicts() { return new NewTopic(KafkaTopics.VERDICTS, 1, (short) 1); }
    @Bean NewTopic ciEventsDlq() { return new NewTopic(KafkaTopics.EVENTS_DLQ, 1, (short) 1); }

    @Bean CommonErrorHandler ciEventsErrorHandler(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, error) -> new org.apache.kafka.common.TopicPartition(KafkaTopics.EVENTS_DLQ, record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(250L, 2L));
    }

    @Bean(name = "ciEventsKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<String, String> ciEventsFactory(
            org.springframework.kafka.core.ConsumerFactory<String, String> consumerFactory,
            CommonErrorHandler errorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
