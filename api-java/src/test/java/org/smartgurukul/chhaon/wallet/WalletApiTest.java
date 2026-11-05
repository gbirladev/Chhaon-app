package org.smartgurukul.chhaon.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.security.JwtService;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.FakeWalletStorage;
import org.smartgurukul.chhaon.support.Fixtures;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Reports wallet through the backend (docs/TODO.md, "Reports tab and the login token"): records run as the caregiver (the database's rules decide),
 * files only in the person's own folder, the family circle only, and the database's limits come back as reasons.
 */
class WalletApiTest extends DbTest {

    @Autowired JwtService jwt;
    @Autowired FakeWalletStorage storage;
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void resetStorage() {
        storage.reset();
    }

    private String bearer(UUID userId) {
        return "Bearer " + jwt.issueToken(userId, false);
    }

    private JsonNode slot(Fixtures.Family f, String kind) throws Exception {
        String body = mvc.perform(post("/api/v1/recipients/{id}/documents/uploads", f.recipientId()).header("Authorization", bearer(f.userId()))
                        .contentType("application/json").content("{\"kind\":\"" + kind + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String create(Fixtures.Family f, String filesJson, int expect) throws Exception {
        return mvc.perform(post("/api/v1/recipients/{id}/documents", f.recipientId()).header("Authorization", bearer(f.userId()))
                        .contentType("application/json")
                        .content("{\"title\":\"Sugar test\",\"category\":\"lab\",\"docDate\":\"2026-10-01\",\"doctor\":\"Dr Rao\",\"files\":" + filesJson + "}"))
                .andExpect(status().is(expect)).andReturn().getResponse().getContentAsString();
    }

    private static String file(String path, String thumb, String mime) {
        return "{\"path\":\"" + path + "\",\"thumbPath\":" + (thumb == null ? "null" : "\"" + thumb + "\"") + ",\"mime\":\"" + mime + "\",\"sizeBytes\":2048}";
    }

    @Test
    void aReportIsSavedListedLinkedEditedAndDeleted_inThePersonsOwnFolder() throws Exception {
        Fixtures.Family f = fx.family("en", false);
        JsonNode photo = slot(f, "jpg");
        assertThat(photo.get("path").asText()).matches(f.recipientId() + "/[0-9a-f]{32}\\.jpg");
        assertThat(photo.get("thumbPath").asText()).isEqualTo(photo.get("path").asText().replace(".jpg", "-t.jpg"));
        assertThat(photo.get("uploadUrl").asText()).startsWith("/object/upload/sign/reports/" + f.recipientId() + "/");
        JsonNode pdf = slot(f, "pdf");
        assertThat(pdf.get("thumbPath").isNull()).isTrue();

        String saved = create(f, "[" + file(photo.get("path").asText(), photo.get("thumbPath").asText(), "image/jpeg") + "]", 200);
        String docId = json.readTree(saved).get("id").asText();
        assertThat(fx.jdbc().queryForObject("select uploaded_by from documents where id = ?::uuid", UUID.class, docId)).isEqualTo(f.userId());

        mvc.perform(get("/api/v1/recipients/{id}/documents", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title", is("Sugar test")))
                .andExpect(jsonPath("$[0].docDate", is("2026-10-01")))
                .andExpect(jsonPath("$[0].files", hasSize(1)))
                .andExpect(jsonPath("$[0].files[0].mime", is("image/jpeg")));
        mvc.perform(get("/api/v1/recipients/{id}/documents/usage", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.used", is(1)));

        // Links only for this person's saved files: an unknown path is left out.
        mvc.perform(post("/api/v1/recipients/{id}/documents/links", f.recipientId()).header("Authorization", bearer(f.userId()))
                        .contentType("application/json")
                        .content("{\"paths\":[\"" + photo.get("thumbPath").asText() + "\",\"" + f.recipientId() + "/other.jpg\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$['" + photo.get("thumbPath").asText() + "']", startsWith("/object/sign/reports/")));

        mvc.perform(put("/api/v1/documents/{id}", docId).header("Authorization", bearer(f.userId())).contentType("application/json")
                        .content("{\"title\":\"HbA1c\",\"category\":\"lab\",\"docDate\":null,\"doctor\":\"\",\"notes\":\"fasting\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("HbA1c")))
                .andExpect(jsonPath("$.doctor", nullValue()))
                .andExpect(jsonPath("$.notes", is("fasting")));

        // A second page, then removing it; the last page cannot be removed (delete the report instead).
        mvc.perform(post("/api/v1/documents/{id}/files", docId).header("Authorization", bearer(f.userId())).contentType("application/json")
                        .content("{\"files\":[" + file(pdf.get("path").asText(), null, "application/pdf") + "]}"))
                .andExpect(status().isOk());
        String pageId = fx.jdbc().queryForObject("select id::text from document_files where document_id = ?::uuid and position = 1", String.class, docId);
        mvc.perform(delete("/api/v1/documents/{id}/files/{fileId}", docId, pageId).header("Authorization", bearer(f.userId())))
                .andExpect(status().isNoContent());
        assertThat(storage.removed).containsExactly(pdf.get("path").asText());
        String firstPage = fx.jdbc().queryForObject("select id::text from document_files where document_id = ?::uuid", String.class, docId);
        mvc.perform(delete("/api/v1/documents/{id}/files/{fileId}", docId, firstPage).header("Authorization", bearer(f.userId())))
                .andExpect(status().isConflict());

        mvc.perform(delete("/api/v1/documents/{id}", docId).header("Authorization", bearer(f.userId())))
                .andExpect(status().isNoContent());
        assertThat(storage.removed).contains(photo.get("path").asText(), photo.get("thumbPath").asText());
        assertThat(fx.jdbc().queryForObject("select count(*) from documents where recipient_id = ?", Integer.class, f.recipientId())).isZero();
    }

    @Test
    void onlyTheFamilyCircle_andNeverAnotherPersonsFolder() throws Exception {
        Fixtures.Family f = fx.family("en", false);
        Fixtures.Family stranger = fx.family("en", false);
        JsonNode photo = slot(f, "png");
        String docId = json.readTree(create(f, "[" + file(photo.get("path").asText(), null, "image/png") + "]", 200)).get("id").asText();

        mvc.perform(get("/api/v1/recipients/{id}/documents", f.recipientId()).header("Authorization", bearer(stranger.userId())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents/{id}", docId).header("Authorization", bearer(stranger.userId())))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/documents/{id}", docId).header("Authorization", bearer(stranger.userId())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/recipients/{id}/documents/uploads", f.recipientId()).header("Authorization", bearer(stranger.userId()))
                        .contentType("application/json").content("{\"kind\":\"jpg\"}"))
                .andExpect(status().isNotFound());
        // A family member sees it too.
        UUID sister = fx.member(f, "Sister");
        mvc.perform(get("/api/v1/documents/{id}", docId).header("Authorization", bearer(sister)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title", is("Sugar test")));

        // A file in another person's folder is refused by the database (its hint comes back), and the upload is cleaned up.
        create(stranger, "[" + file(f.recipientId() + "/abc.jpg", null, "image/jpeg") + "]", 409);
        // Discard never touches a saved file or another person's folder.
        mvc.perform(post("/api/v1/recipients/{id}/documents/discard", f.recipientId()).header("Authorization", bearer(f.userId()))
                        .contentType("application/json")
                        .content("{\"paths\":[\"" + photo.get("path").asText() + "\",\"" + stranger.recipientId() + "/x.jpg\",\"" + f.recipientId() + "/unsaved.jpg\"]}"))
                .andExpect(status().isNoContent());
        assertThat(storage.removed).containsExactly(f.recipientId() + "/unsaved.jpg");
    }

    @Test
    void tooManyPages_badKinds_andStorageOffAreRefusedWithAReason() throws Exception {
        Fixtures.Family f = fx.family("en", false);
        StringBuilder eleven = new StringBuilder("[");
        for (int i = 0; i < 11; i++) eleven.append(i == 0 ? "" : ",").append(file(f.recipientId() + "/p" + i + ".jpg", null, "image/jpeg"));
        String body = create(f, eleven.append("]").toString(), 409);
        assertThat(body).contains("too_many_files");

        mvc.perform(post("/api/v1/recipients/{id}/documents/uploads", f.recipientId()).header("Authorization", bearer(f.userId()))
                        .contentType("application/json").content("{\"kind\":\"exe\"}"))
                .andExpect(status().isBadRequest());
        storage.switchOn(false);
        mvc.perform(post("/api/v1/recipients/{id}/documents/uploads", f.recipientId()).header("Authorization", bearer(f.userId()))
                        .contentType("application/json").content("{\"kind\":\"jpg\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail", is("storage_off")));
        // The list still works without storage (only the files need it).
        mvc.perform(get("/api/v1/recipients/{id}/documents", f.recipientId()).header("Authorization", bearer(f.userId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }
}
