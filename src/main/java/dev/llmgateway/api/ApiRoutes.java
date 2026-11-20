package dev.llmgateway.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/** OpenAI-compatible endpoints. Point any OpenAI SDK at http://host:8080/v1 and it just works. */
@Configuration
public class ApiRoutes {

    @Bean
    RouterFunction<ServerResponse> openAiCompatibleRoutes(ChatHandler handler) {
        return RouterFunctions.route()
                .POST("/v1/chat/completions", handler::chatCompletions)
                .GET("/v1/models", handler::models)
                .build();
    }
}
