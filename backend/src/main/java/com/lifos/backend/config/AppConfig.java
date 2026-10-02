package com.lifos.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.netty.channel.ChannelOption;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public WebClient webClient(AiFoundationProperties aiFoundationProperties) {
        HttpClient httpClient = HttpClient.create()
                .option(
                        ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(aiFoundationProperties.getStreaming().getConnectTimeout().toMillis()))
                .responseTimeout(aiFoundationProperties.getStreaming().getResponseTimeout());

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Primary async executor for all embedding and AI background work.
     * Marked @Primary so bare @Async annotations route here by default.
     * CallerRunsPolicy is a safe fallback: if the queue is full the calling
     * thread (event dispatcher) runs the task itself rather than dropping it,
     * which provides natural back-pressure instead of silent job loss.
     */
    @Primary
    @Bean(name = "aiTaskExecutor")
    public Executor aiTaskExecutor(AiFoundationProperties aiFoundationProperties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(aiFoundationProperties.getAsync().getCorePoolSize());
        executor.setMaxPoolSize(aiFoundationProperties.getAsync().getMaxPoolSize());
        executor.setQueueCapacity(aiFoundationProperties.getAsync().getQueueCapacity());
        executor.setThreadNamePrefix(aiFoundationProperties.getAsync().getThreadNamePrefix());
        // CallerRunsPolicy: if queue saturated, run in dispatcher thread instead of dropping
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    public WebMvcConfigurer jackson2ConverterConfigurer(ObjectMapper objectMapper) {
        return new WebMvcConfigurer() {
            @Override
            public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                converters.add(0, new MappingJackson2HttpMessageConverter(objectMapper));
            }
        };
    }
}
