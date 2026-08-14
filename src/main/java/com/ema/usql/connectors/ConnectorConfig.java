package com.ema.usql.connectors;

import com.ema.usql.connectors.api.ConnectorSdk;
import com.ema.usql.telemetry.api.Telemetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Spring configuration for connector beans.
 * Registers JiraConnector and GithubConnector with configurable base URLs.
 * Bean names ("jira", "github") are used by ConnectorRegistry for map-based injection.
 */
@Configuration
public class ConnectorConfig {

    @Value("${usql.connectors.jira.url:http://localhost:8081}")
    private String jiraBaseUrl;

    @Value("${usql.connectors.github.url:http://localhost:8082}")
    private String githubBaseUrl;

    @Bean("jira")
    public ConnectorSdk jiraConnector(RestClient.Builder restClientBuilder, Telemetry telemetry) {
        return new JiraConnector(jiraBaseUrl, restClientBuilder.clone(), telemetry);
    }

    @Bean("github")
    public ConnectorSdk githubConnector(RestClient.Builder restClientBuilder, Telemetry telemetry) {
        return new GithubConnector(githubBaseUrl, restClientBuilder.clone(), telemetry);
    }
}
