package org.smartgurukul.chhaon.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for integration tests: Spring context against the throwaway test database, a recording
 * WhatsApp sender, a fake gateway client, and explicit cleanup after each test. Skipped when the database
 * is not reachable.
 */
@RequiresTestDatabase
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
public abstract class DbTest {

    @Autowired
    protected Fixtures fx;

    @Autowired
    protected RecordingWhatsAppSender wa;

    @Autowired
    protected MockMvc mvc;

    protected GatewayClient gateway;

    @BeforeEach
    void resetSender() {
        wa.reset();
        gateway = new GatewayClient(mvc);
    }

    @AfterEach
    void cleanUp() {
        fx.cleanup();
    }
}
