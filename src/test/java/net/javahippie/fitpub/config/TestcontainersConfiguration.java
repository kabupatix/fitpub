package net.javahippie.fitpub.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers configuration for tests and dev mode (via spring-boot:test-run).
 * Automatically starts a PostgreSQL container with PostGIS extension.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("postgis/postgis:16-3.4")
                        .asCompatibleSubstituteFor("postgres")
        )
                .withDatabaseName("testdb")
                .withUsername("test")
                .withPassword("test")
                .waitingFor(new HostPortWaitStrategy())
                .withReuse(true);
    }
}
