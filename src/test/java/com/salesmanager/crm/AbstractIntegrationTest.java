package com.salesmanager.crm;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Testcontainers Postgres setup for all integration tests, using the Testcontainers
 * "singleton container" pattern: ONE container is started manually (no {@code @Testcontainers}
 * / {@code @Container} JUnit lifecycle management) and reused across every subclass/test class
 * for the life of the JVM. This is deliberate: with the JUnit-managed lifecycle, the container
 * gets stopped after each test class and a fresh one started for the next, but Spring's
 * ApplicationContext cache does not factor in the new container's (changed) port into its
 * cache key, so it keeps reusing a DataSource pointed at the now-dead container - causing
 * every test after the first class to fail with connection errors. Never call POSTGRES.stop();
 * the Ryuk reaper (part of Testcontainers) cleans it up when the JVM exits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("salesmanager")
            .withUsername("salesmanager")
            .withPassword("salesmanager")
            .withReuse(false);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerPgProperties(DynamicPropertyRegistry registry) {
        // Flyway runs migrations (including creating the restricted "salesmanager_app" role)
        // as the container's privileged bootstrap superuser.
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        // The application's own runtime datasource connects as the non-superuser role that
        // V1__init_schema.sql creates, so Postgres RLS actually applies to app queries -
        // superusers unconditionally bypass RLS regardless of FORCE ROW LEVEL SECURITY.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "salesmanager_app");
        registry.add("spring.datasource.password", () -> "salesmanager_app");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    protected String baseUrl() {
        return "http://localhost:" + port + "/api/v1";
    }
}
