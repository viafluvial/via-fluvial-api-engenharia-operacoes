package br.com.viafluvial.engenhariaoperacoes;

import br.com.viafluvial.engenhariaoperacoes.config.EngineeringProperties;
import br.com.viafluvial.engenhariaoperacoes.config.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({SecurityProperties.class, EngineeringProperties.class})
public class EngenhariaOperacoesApplication {

    public static void main(String[] args) {
        SpringApplication.run(EngenhariaOperacoesApplication.class, args);
    }
}
