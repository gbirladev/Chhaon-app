package org.smartgurukul.chhaon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Chhaon backend — family health & care API.
 *
 * Replaces the earlier Python FastAPI service. Talks to the (Supabase) Postgres database
 * directly as the owner role; access control is enforced in the application layer (Spring
 * Security + JWT), not via Postgres RLS.
 */
@SpringBootApplication
@EnableScheduling
public class ChhaonApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChhaonApplication.class, args);
    }
}
