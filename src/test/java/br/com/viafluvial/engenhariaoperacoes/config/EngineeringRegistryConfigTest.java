package br.com.viafluvial.engenhariaoperacoes.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class EngineeringRegistryConfigTest {

    private EngineeringProperties bindApplicationYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new PropertiesPropertySource("application", properties));
        return Binder.get(environment)
                .bind("engineering", EngineeringProperties.class)
                .orElseThrow(() -> new IllegalStateException("engineering não configurado"));
    }

    @Test
    void shouldRegisterFretesCargasOnPort18022() {
        EngineeringProperties properties = bindApplicationYaml();

        EngineeringProperties.ServiceEntry fretes = properties.getRegistry().getServices().stream()
                .filter(entry -> "fretes-cargas".equals(entry.getKey()))
                .findFirst()
                .orElseThrow();

        assertThat(fretes.getName()).isEqualTo("api-fretes-cargas");
        assertThat(fretes.getCategory()).isEqualTo("Logística e Cargas");
        assertThat(fretes.getLocalDirectBaseUrl()).endsWith(":18022/api/v1");
    }

    @Test
    void shouldKeepServiceKeysUniqueAndExistingEntriesIntact() {
        EngineeringProperties properties = bindApplicationYaml();

        var keys = properties.getRegistry().getServices().stream().map(EngineeringProperties.ServiceEntry::getKey).toList();
        assertThat(keys).doesNotHaveDuplicates();
        assertThat(keys).hasSize(22).contains("avaliacoes-experiencia", "engenharia-operacoes", "fretes-cargas");
    }
}
