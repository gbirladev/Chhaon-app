package org.smartgurukul.chhaon.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.smartgurukul.chhaon.support.DbTest;
import org.smartgurukul.chhaon.support.FakeMedicinePhotoStore;
import org.smartgurukul.chhaon.support.Fixtures;
import org.smartgurukul.chhaon.support.Fixtures.Family;
import org.smartgurukul.chhaon.support.Fixtures.Med;
import org.smartgurukul.chhaon.support.GatewayClient;
import org.smartgurukul.chhaon.support.RecordingWhatsAppSender.Image;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 5: the medicine photo in the WhatsApp reminder. With a photo the reminder is an image with the reminder text as its
 * caption; the gateway's message id is stored like before so replies still match. Any photo problem sends the plain text in
 * the same tick.
 */
class SchedulerPhotoReminderTest extends DbTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeMedicinePhotoStore storage;

    @BeforeEach
    void storageOn() {
        storage.reset();
        storage.switchOn(true);
    }

    @AfterEach
    void storageOff() {
        storage.reset();
    }

    private SchedulerService scheduler(Family f) {
        SchedulerService s = new SchedulerService(jdbc, wa, true, 60, 180, 120, 24, f.recipientId().toString());
        s.setMedicinePhotoStore(storage);
        return s;
    }

    static byte[] jpeg(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) img.setRGB(x, y, (x * 7 + y * 13) & 0xFFFFFF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", out);
        return out.toByteArray();
    }

    /** A medicine with a photo stored at "recipientId/strip.jpg" and a dose due 5 minutes ago. */
    private Med withPhoto(Family f, String name) throws Exception {
        Med m = fx.medicine(f, name, "500 mg", 20, LocalTime.of(3, 0));
        String path = f.recipientId() + "/strip.jpg";
        jdbc.update("update public.medicines set photo_url = ? where id = ?", path, m.medicineId());
        storage.put(path, jpeg(64, 48));
        return m;
    }

    private UUID due(Med m) {
        return fx.dose(m.scheduleId(), Instant.now().minus(5, ChronoUnit.MINUTES), "pending", null, null);
    }

    @Test
    void withAPhotoTheReminderIsAnImageWithTheTextAsCaption_andItsIdIsStored() throws Exception {
        Family f = fx.family("en", true);
        UUID dose = due(withPhoto(f, "Metformin"));

        scheduler(f).scheduledTick();

        List<Image> images = wa.images();
        assertThat(images).hasSize(1);
        Image img = images.get(0);
        assertThat(img.to()).isEqualTo(f.phone());
        assertThat(img.caption())
                .isEqualTo("Namaste Papa 🙏\nMedicine time: *Metformin*\n500 mg\n\nTaken it? Just reply *1*");
        assertThat(img.jpeg()).isNotEmpty();
        assertThat(wa.sentTo(f.phone())).hasSize(1);   // one message only, no extra text
        assertThat(fx.dose(dose)).containsEntry("status", "sent").containsEntry("whatsapp_message_id", img.messageId());
    }

    @Test
    void aSwipeReplyToThePhotoReminderMarksTheDoseTaken() throws Exception {
        Family f = fx.family("en", true);
        UUID dose = due(withPhoto(f, "Metformin"));
        scheduler(f).scheduledTick();
        String imageId = wa.images().get(0).messageId();
        // a NEWER open reminder for another medicine: a plain "1" would answer that one, the swipe-reply must answer the photo
        Med other = fx.medicine(f, "Amlodipine", "5 mg", 20, LocalTime.of(4, 0));
        UUID newer = fx.dose(other.scheduleId(), Instant.now().minus(1, ChronoUnit.MINUTES), "sent", Instant.now(), Fixtures.messageId());

        assertThat(gateway.post(gateway.received(Fixtures.messageId(), GatewayClient.jid(f.phone()), "1", imageId)).status())
                .isEqualTo(200);

        assertThat(fx.doseStatus(dose)).isEqualTo("taken");
        assertThat(fx.doseStatus(newer)).isEqualTo("sent");
    }

    @Test
    void storageOff_sendsThePlainText() throws Exception {
        Family f = fx.family("en", true);
        UUID dose = due(withPhoto(f, "Metformin"));
        storage.switchOn(false);

        scheduler(f).scheduledTick();

        assertThat(wa.images()).isEmpty();
        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(wa.sentTo(f.phone()).get(0).text()).contains("*Metformin*");
        assertThat(fx.dose(dose)).containsEntry("status", "sent").containsEntry("whatsapp_message_id", "test-out-1");
    }

    @Test
    void photoMissingInStorage_sendsThePlainText() throws Exception {
        Family f = fx.family("en", true);
        Med m = fx.medicine(f, "Metformin", "500 mg", 20, LocalTime.of(3, 0));
        jdbc.update("update public.medicines set photo_url = ? where id = ?", f.recipientId() + "/gone.jpg", m.medicineId());
        UUID dose = due(m);

        scheduler(f).scheduledTick();

        assertThat(wa.images()).isEmpty();
        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(fx.dose(dose)).containsEntry("whatsapp_message_id", "test-out-1");
    }

    @Test
    void storageError_sendsThePlainText() throws Exception {
        Family f = fx.family("en", true);
        UUID dose = due(withPhoto(f, "Metformin"));
        storage.breakStorage(true);

        scheduler(f).scheduledTick();

        assertThat(wa.images()).isEmpty();
        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(fx.dose(dose)).containsEntry("whatsapp_message_id", "test-out-1");
    }

    @Test
    void notAnImage_sendsThePlainText() throws Exception {
        Family f = fx.family("en", true);
        Med m = withPhoto(f, "Metformin");
        storage.put(f.recipientId() + "/strip.jpg", "this is not a picture".getBytes());
        UUID dose = due(m);

        scheduler(f).scheduledTick();

        assertThat(wa.images()).isEmpty();
        assertThat(fx.dose(dose)).containsEntry("whatsapp_message_id", "test-out-1");
    }

    @Test
    void gatewayRefusesMedia_sendsThePlainTextInTheSameTick() throws Exception {
        Family f = fx.family("en", true);
        UUID dose = due(withPhoto(f, "Metformin"));
        wa.failMedia(true);

        scheduler(f).scheduledTick();

        assertThat(wa.images()).isEmpty();
        assertThat(wa.sentTo(f.phone())).hasSize(1);
        assertThat(wa.sentTo(f.phone()).get(0).text()).contains("*Metformin*");
        assertThat(fx.dose(dose)).containsEntry("status", "sent").containsEntry("whatsapp_message_id", "test-out-1");
    }

    @Test
    void whenEvenTheTextFails_theDoseIsHandedBackForTheNextTick() throws Exception {
        Family f = fx.family("en", true);
        UUID dose = due(withPhoto(f, "Metformin"));
        wa.failSends(true);

        scheduler(f).scheduledTick();

        assertThat(fx.doseStatus(dose)).isEqualTo("pending");
        assertThat(wa.sent()).isEmpty();
    }

    @Test
    void aHelperGetsThePhotoWithTheThirdPersonCaption() throws Exception {
        Family f = fx.family("en", true);
        String helper = fx.track(Fixtures.randomPhone());
        jdbc.update("update care_recipients set reminder_to = 'helper', reminder_name = 'Sunita', reminder_relation = 'maid', " +
                "whatsapp_phone = ? where id = ?", helper, f.recipientId());
        fx.consent(f.recipientId());
        Med m = withPhoto(f, "Metformin");
        jdbc.update("update public.medicines set pill_colour = 'blue', pill_shape = 'round' where id = ?", m.medicineId());
        due(m);

        scheduler(f).scheduledTick();

        assertThat(wa.images()).hasSize(1);
        assertThat(wa.images().get(0).to()).isEqualTo(helper);
        assertThat(wa.images().get(0).caption()).startsWith("Namaste Sunita ji 🙏").contains("Time for Papa's medicine: *Metformin*\n500 mg · 🔵 Blue round tablet").endsWith("Given it to Papa? Just reply *1*");
    }

    @Test
    void aPregnancyReminderKeepsItsWordingAsTheCaption() throws Exception {
        Family f = fx.family("en", true);
        LocalDate today = ZonedDateTime.now(f.zone()).toLocalDate();
        jdbc.update("update public.care_recipients set care_type = 'pregnant', due_date = ?, call_name = null, name = 'Shivani Sharma' where id = ?",
                today.plusDays(112), f.recipientId());
        due(withPhoto(f, "Iron + folic acid"));

        scheduler(f).scheduledTick();

        assertThat(wa.images()).hasSize(1);
        assertThat(wa.images().get(0).caption()).matches("(?s)Good (morning|afternoon|evening) Shivani .*")
                .contains("\nWeek 24 starts today 💛\nMedicine time: *Iron + folic acid*\n500 mg")
                .endsWith("Taken it? Just reply *1*");
    }

    @Test
    void theSpringSchedulerHasThePhotoStore() {
        // the real (Spring-made) scheduler gets the store injected: in tests the fake one
        assertThat(applicationScheduler).isNotNull();
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(applicationScheduler, "photos")).isSameAs(storage);
    }

    @Autowired
    SchedulerService applicationScheduler;
}
