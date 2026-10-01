package com.devPilot.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        // Use an in-memory H2 datasource so the test never needs a real Postgres DB
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // Disable OAuth2 auto-config that requires client-id / client-secret
        "spring.security.oauth2.client.registration.github.client-id=test-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-secret",
        // Stub out app-specific required properties
        "app.frontend-url=http://localhost:3000",
        "app.cors.allowed-origins=http://localhost:3000",
        "app.token-encryption-password=test-password-16",
        "app.token-encryption-salt=deadbeef1234abcd",
        "OPENAI_API_KEY=test-key"
})
class BackendApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the Spring context starts without errors
    }
}
