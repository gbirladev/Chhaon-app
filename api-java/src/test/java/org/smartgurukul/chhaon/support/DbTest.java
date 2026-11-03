package org.smartgurukul.chhaon.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for integration tests: Spring context against the throwaway test database, a recording
 * WhatsApp sender, and explicit cleanup after each test. Skipped when the database is not reachable.
 */
@RequiresTestDatabase
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
public abstract class DbTest {

    @Autowired
    protected Fixtures fx;

    @Autowired
    protected RecordingWhatsAppSender wa;

    @BeforeEach
    void resetSender() {
        wa.reset();
    }

    @AfterEach
    void cleanUp() {
        fx.cleanup();
    }
}
