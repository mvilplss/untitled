package org.example.config;

import org.example.dws.DwsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AgentProperties.class, DwsProperties.class})
public class AgentConfig {
}
