package org.smartgurukul.chhaon.recipient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Your doctors" (2026-11-06): PUT /api/v1/recipients/{id}/doctors, and the profile form never loses the list. */
class DoctorsApiTest extends DbTest {

    @Autowired JwtService jwt;

    private final List<UUID> owners = new ArrayList<>();

    @AfterEach
    void removeCirclesMadeThroughTheApi() {
        for (UUID u : owners) fx.jdbc().update("delete from care_circles where created_by = ?", u);
        owners.clear();
    }

    private UUID owner() {
        UUID u = fx.user("Rohit");
        owners.add(u);
        return u;
    }

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    private static final String PROFILE = "{\"name\":\"Ramesh Sharma\",\"relation\":\"father\",\"timezone\":\"Asia/Kolkata\",\"language\":\"hi\"";

    private UUID create(UUID user) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/recipients").header("Authorization", bearer(user))
                        .contentType("application/json").content(PROFILE + "}"))
                .andExpect(status().isCreated()).andReturn();
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(r.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        return UUID.fromString(m.group(1));
    }

    private String stored(UUID id) {
        return fx.jdbc().queryForObject("select doctor_contacts::text from care_recipients where id = ?", String.class, id);
    }

    @Test
    void theFamilySavesDoctorsWithCleanTextAndANormalisedPhone() throws Exception {
        UUID user = owner();
        UUID id = create(user);
        mvc.perform(put("/api/v1/recipients/{id}/doctors", id).header("Authorization", bearer(user)).contentType("application/json")
                        .content("{\"doctors\":[{\"name\":\"  Dr  Anil\\u202E Mehta \",\"speciality\":\"Heart\",\"clinic\":\"City Clinic\","
                                + "\"phone\":\"99999 00001\",\"address\":\"12 MG Road, Indore\"},"
                                + "{\"name\":\"\",\"phone\":\"\",\"clinic\":\" \"},"
                                + "{\"name\":\"Dr Rao\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctorContacts", hasSize(2)))
                .andExpect(jsonPath("$.doctorContacts[0].name", is("Dr Anil Mehta")))
                .andExpect(jsonPath("$.doctorContacts[0].phone", is("+919999900001")))
                .andExpect(jsonPath("$.doctorContacts[0].speciality", is("Heart")))
                .andExpect(jsonPath("$.doctorContacts[0].clinic", is("City Clinic")))
                .andExpect(jsonPath("$.doctorContacts[0].address", is("12 MG Road, Indore")))
                .andExpect(jsonPath("$.doctorContacts[1].name", is("Dr Rao")));
        // Empty fields are not stored as null keys.
        assertThat(stored(id)).contains("\"Dr Rao\"").doesNotContain("null");
    }

    @Test
    void aMissingNameABadPhoneOrASixthDoctorIsRefusedWithoutEchoingThePhone() throws Exception {
        UUID user = owner();
        UUID id = create(user);
        String auth = bearer(user);
        mvc.perform(put("/api/v1/recipients/{id}/doctors", id).header("Authorization", auth).contentType("application/json")
                        .content("{\"doctors\":[{\"clinic\":\"City Clinic\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/recipients/{id}/doctors", id).header("Authorization", auth).contentType("application/json")
                        .content("{\"doctors\":[{\"name\":\"Dr Mehta\",\"phone\":\"12345\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("12345"))));
        StringBuilder six = new StringBuilder("{\"doctors\":[");
        for (int i = 1; i <= 6; i++) six.append(i > 1 ? "," : "").append("{\"name\":\"Dr ").append(i).append("\"}");
        mvc.perform(put("/api/v1/recipients/{id}/doctors", id).header("Authorization", auth).contentType("application/json")
                        .content(six.append("]}").toString()))
                .andExpect(status().isBadRequest());
        assertThat(stored(id)).isEqualTo("[]");
    }

    @Test
    void someoneOutsideTheFamilyCannotEditTheList() throws Exception {
        UUID id = create(owner());
        UUID stranger = owner();
        mvc.perform(put("/api/v1/recipients/{id}/doctors", id).header("Authorization", bearer(stranger)).contentType("application/json")
                        .content("{\"doctors\":[{\"name\":\"Dr Mehta\"}]}"))
                .andExpect(status().isNotFound());
        assertThat(stored(id)).isEqualTo("[]");
    }

    @Test
    void savingTheProfileKeepsTheDoctors() throws Exception {
        UUID user = owner();
        UUID id = create(user);
        String auth = bearer(user);
        mvc.perform(put("/api/v1/recipients/{id}/doctors", id).header("Authorization", auth).contentType("application/json")
                        .content("{\"doctors\":[{\"name\":\"Dr Mehta\",\"speciality\":\"Heart\",\"phone\":\"+919999900002\",\"clinic\":\"City Clinic\",\"address\":\"Indore\"}]}"))
                .andExpect(status().isOk());
        // The new profile form leaves doctorContacts out.
        mvc.perform(put("/api/v1/recipients/{id}", id).header("Authorization", auth).contentType("application/json").content(PROFILE + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctorContacts[0].clinic", is("City Clinic")));
        // An older app sends back exactly what it read: nothing changes.
        mvc.perform(put("/api/v1/recipients/{id}", id).header("Authorization", auth).contentType("application/json")
                        .content(PROFILE + ",\"doctorContacts\":[{\"name\":\"Dr Mehta\",\"phone\":\"+919999900002\",\"speciality\":\"Heart\",\"clinic\":\"City Clinic\",\"address\":\"Indore\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctorContacts", hasSize(1)))
                .andExpect(jsonPath("$.doctorContacts[0].address", is("Indore")));
        assertThat(stored(id)).contains("City Clinic").contains("+919999900002");
    }
}
