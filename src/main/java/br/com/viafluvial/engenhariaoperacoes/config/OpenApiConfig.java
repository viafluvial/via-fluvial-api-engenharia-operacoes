package br.com.viafluvial.engenhariaoperacoes.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@io.swagger.v3.oas.annotations.OpenAPIDefinition(
    info = @io.swagger.v3.oas.annotations.info.Info(
        title = "api-engenharia-operacoes",
        version = "1.0.0",
        description = "Disponibiliza recursos de engenharia operacional, monitoramento de execucao e diagnosticos tecnicos para sustentacao da plataforma."),
    servers = {
        @io.swagger.v3.oas.annotations.servers.Server(url = "/engenharia-operacoes/api/v1", description = "Entrypoint padrao no API Gateway (Gravitee).")
    },
    security = {
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    }
)
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("api-engenharia-operacoes")
                .version("1.0.0")
                .description("Disponibiliza recursos de engenharia operacional, monitoramento de execucao e diagnosticos tecnicos para sustentacao da plataforma."))
            .servers(List.of(new Server()
                .url("/engenharia-operacoes/api/v1")
                .description("Entrypoint padrao no API Gateway (Gravitee).")))
            .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
