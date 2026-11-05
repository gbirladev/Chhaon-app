package org.smartgurukul.chhaon.whatsapp;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading and shrinking the medicine photo (no network: download is replaced). */
class MedicinePhotoStoreTest {

    private static byte[] image(String format, int w, int h, boolean noise) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Random r = new Random(7);
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) img.setRGB(x, y, noise ? r.nextInt() : 0xFF3366AA);
        if (format.equals("jpeg")) {
            BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            rgb.getGraphics().drawImage(img, 0, 0, null);
            img = rgb;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    /** A store that is switched on and serves one file from memory. */
    private static MedicinePhotoStore storeWith(String path, byte[] bytes) {
        return new MedicinePhotoStore("https://example.invalid", "not-a-real-key") {
            @Override
            protected byte[] download(String p) {
                return p.equals(path) ? bytes : null;
            }
        };
    }

    @Test
    void aSmallJpegIsSentAsItIs() throws Exception {
        byte[] jpeg = image("jpeg", 200, 150, false);
        assertThat(MedicinePhotoStore.shrinkToJpeg(jpeg, MedicinePhotoStore.MAX_BYTES)).isSameAs(jpeg);
    }

    @Test
    void aBigPngBecomesAJpegOfAtMostOneMegabyte() throws Exception {
        byte[] png = image("png", 2400, 1800, true);   // random pixels: several MB as PNG
        assertThat(png.length).isGreaterThan(MedicinePhotoStore.MAX_BYTES);

        byte[] out = MedicinePhotoStore.shrinkToJpeg(png, MedicinePhotoStore.MAX_BYTES);

        assertThat(out).isNotNull();
        assertThat(out.length).isLessThanOrEqualTo(MedicinePhotoStore.MAX_BYTES);
        assertThat(MedicinePhotoStore.isJpeg(out)).isTrue();
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(out));
        assertThat(Math.max(back.getWidth(), back.getHeight())).isLessThanOrEqualTo(1280);
    }

    @Test
    void notAnImageGivesNothing() {
        assertThat(MedicinePhotoStore.shrinkToJpeg("hello".getBytes(), MedicinePhotoStore.MAX_BYTES)).isNull();
        assertThat(MedicinePhotoStore.shrinkToJpeg(new byte[0], MedicinePhotoStore.MAX_BYTES)).isNull();
        assertThat(MedicinePhotoStore.shrinkToJpeg(null, MedicinePhotoStore.MAX_BYTES)).isNull();
    }

    @Test
    void onlyFilesInThePersonsOwnFolderAreRead() {
        UUID me = UUID.randomUUID();
        assertThat(MedicinePhotoStore.allowedPath(me, me + "/abc123.jpg")).isTrue();
        assertThat(MedicinePhotoStore.allowedPath(me, UUID.randomUUID() + "/abc123.jpg")).isFalse();
        assertThat(MedicinePhotoStore.allowedPath(me, me + "/../other/abc.jpg")).isFalse();
        assertThat(MedicinePhotoStore.allowedPath(me, me + "//abc.jpg")).isFalse();
        assertThat(MedicinePhotoStore.allowedPath(me, me + "/a.jpg?x=1")).isFalse();
        assertThat(MedicinePhotoStore.allowedPath(me, "abc.jpg")).isFalse();
        assertThat(MedicinePhotoStore.allowedPath(null, me + "/abc.jpg")).isFalse();
    }

    @Test
    void blankSettingsOrAPlainHttpAddressMeanPhotosOff() {
        assertThat(new MedicinePhotoStore("", "").isConfigured()).isFalse();
        assertThat(new MedicinePhotoStore("https://x.supabase.co", "").isConfigured()).isFalse();
        assertThat(new MedicinePhotoStore("", "k").isConfigured()).isFalse();
        assertThat(new MedicinePhotoStore("http://x.supabase.co", "k").isConfigured()).isFalse();   // key never sent unencrypted
        assertThat(new MedicinePhotoStore("https://x.supabase.co/", "k").isConfigured()).isTrue();
        assertThat(new MedicinePhotoStore("http://127.0.0.1:54321", "k").isConfigured()).isTrue();  // local Supabase
        assertThat(new MedicinePhotoStore("https://x.supabase.co", "secret-value").toString()).doesNotContain("secret-value");
    }

    @Test
    void reminderJpegReturnsThePhotoOrNothing_neverThrows() throws Exception {
        UUID me = UUID.randomUUID();
        byte[] jpeg = image("jpeg", 100, 100, false);
        MedicinePhotoStore store = storeWith(me + "/a.jpg", jpeg);

        assertThat(store.reminderJpeg(me, me + "/a.jpg")).contains(jpeg);
        assertThat(store.reminderJpeg(me, me + "/missing.jpg")).isEmpty();
        assertThat(store.reminderJpeg(me, null)).isEmpty();
        assertThat(store.reminderJpeg(me, "")).isEmpty();
        assertThat(store.reminderJpeg(UUID.randomUUID(), me + "/a.jpg")).isEmpty();

        MedicinePhotoStore broken = new MedicinePhotoStore("https://example.invalid", "k") {
            @Override
            protected byte[] download(String p) throws IOException {
                throw new IOException("storage_http_500");
            }
        };
        assertThat(broken.reminderJpeg(me, me + "/a.jpg")).isEmpty();

        MedicinePhotoStore off = new MedicinePhotoStore("", "") {
            @Override
            protected byte[] download(String p) {
                throw new AssertionError("storage must not be called when photos are off");
            }
        };
        assertThat(off.reminderJpeg(me, me + "/a.jpg")).isEmpty();
    }
}
