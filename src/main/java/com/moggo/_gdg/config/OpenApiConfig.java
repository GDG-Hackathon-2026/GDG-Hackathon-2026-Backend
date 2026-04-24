package com.moggo._gdg.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI gdgOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("GDG Hackathon 2026 Backend API")
                        .description("2026 GDG 해커톤 백엔드 API 명세")
                        .version("v0.1"))
                .servers(List.of(
                        new Server().url("http://3.39.235.46:8080").description("EC2 (prod)"),
                        new Server().url("http://localhost:8080").description("Local")
                ));
    }
}
