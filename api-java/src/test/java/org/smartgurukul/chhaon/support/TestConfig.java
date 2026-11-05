package org.smartgurukul.chhaon.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** Replaces the real WhatsApp sender with a recording one and adds the data helpers. */
@TestConfiguration
public class TestConfig {

    @Bean
    @Primary
    public RecordingWhatsAppSender recordingWhatsAppSender() {
        return new RecordingWhatsAppSender();
    }

    /** Medicine photos never come from the real Supabase in tests; off unless a test switches it on. */
    @Bean
    @Primary
    public FakeMedicinePhotoStore fakeMedicinePhotoStore() {
        return new FakeMedicinePhotoStore();
    }

    /** The Reports wallet's files never go to the real Supabase in tests. */
    @Bean
    @Primary
    public FakeWalletStorage fakeWalletStorage() {
        return new FakeWalletStorage();
    }

    @Bean
    public Fixtures fixtures(JdbcTemplate jdbc) {
        return new Fixtures(jdbc);
    }
}
