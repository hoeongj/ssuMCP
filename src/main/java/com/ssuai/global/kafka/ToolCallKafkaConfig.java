package com.ssuai.global.kafka;

import java.util.HashMap;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.util.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration
@EnableKafka
@EnableConfigurationProperties(ToolCallKafkaProperties.class)
@ConditionalOnProperty(prefix = "ssuai.kafka", name = "enabled", havingValue = "true")
class ToolCallKafkaConfig {

    private final Environment environment;

    ToolCallKafkaConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    ProducerFactory<String, String> toolCallProducerFactory(
            ToolCallKafkaProperties properties,
            MeterRegistry meterRegistry) {

        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(properties));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, properties.getMaxBlockMs());
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 4000);
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        config.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33_554_432);
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(config);
        factory.addListener(new MicrometerProducerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    KafkaTemplate<String, String> toolCallKafkaTemplate(ProducerFactory<String, String> toolCallProducerFactory) {
        return new KafkaTemplate<>(toolCallProducerFactory);
    }

    @Bean
    KafkaAdmin toolCallKafkaAdmin(ToolCallKafkaProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(properties));
        KafkaAdmin admin = new KafkaAdmin(config);
        admin.setFatalIfBrokerNotAvailable(false);
        return admin;
    }

    @Bean
    NewTopic mcpToolCallTopic(ToolCallKafkaProperties properties) {
        return new NewTopic(properties.getTopic(), properties.getPartitions(), (short) 1);
    }

    @Bean
    ConsumerFactory<String, String> toolCallConsumerFactory(
            ToolCallKafkaProperties properties,
            MeterRegistry meterRegistry) {

        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(properties));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "mcp-tool-call-logger");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);

        DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(config);
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> toolCallKafkaListenerContainerFactory(
            ConsumerFactory<String, String> toolCallConsumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(toolCallConsumerFactory);
        factory.setMissingTopicsFatal(false);
        return factory;
    }

    private String bootstrapServers(ToolCallKafkaProperties properties) {
        if (StringUtils.hasText(properties.getBootstrapServers())) {
            return properties.getBootstrapServers();
        }
        return environment.getProperty("spring.kafka.bootstrap-servers", "");
    }
}
