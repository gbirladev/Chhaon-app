package org.smartgurukul.chhaon.medicine;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.smartgurukul.chhaon.scheduler.SchedulerService;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.Fixtures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Making the doses at once is best effort: when it fails, the medicine is still saved and the save answers success. */
class MedicineDosesNowFailureTest extends DbTest {

    @Autowired JwtService jwt;

    @MockitoSpyBean
    SchedulerService scheduler;

    @Test
    void aFailureMakingTheDosesNeverFailsTheSave() throws Exception {
        Fixtures.Family f = fx.family("en", true);
        Mockito.doThrow(new IllegalStateException("simulated database trouble")).when(scheduler).generateNow(Mockito.any());
        String bearer = "Bearer " + jwt.issueToken(f.userId(), false);

        mvc.perform(post("/api/v1/recipients/{id}/medicines", f.recipientId())
                        .header("Authorization", bearer).contentType("application/json")
                        .content("{\"name\":\"Amlodipine\",\"dosage\":\"5 mg\",\"times\":[\"21:00\"]}"))
                .andExpect(status().isCreated());
        UUID id = fx.jdbc().queryForObject("select id from medicines where recipient_id = ? and name = 'Amlodipine'", UUID.class, f.recipientId());
        assertThat(id).isNotNull();

        mvc.perform(put("/api/v1/medicines/{id}", id).header("Authorization", bearer).contentType("application/json")
                        .content("{\"name\":\"Amlodipine\",\"dosage\":\"5 mg\",\"times\":[\"08:00\",\"21:00\"]}"))
                .andExpect(status().isOk());
        assertThat(fx.jdbc().queryForObject("select count(*) from medicine_schedules where medicine_id = ?", Integer.class, id)).isEqualTo(2);

        Mockito.verify(scheduler, Mockito.times(2)).generateNow(f.recipientId());
    }
}
