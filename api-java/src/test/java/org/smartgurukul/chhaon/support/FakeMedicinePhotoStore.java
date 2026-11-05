package org.smartgurukul.chhaon.support;

import org.smartgurukul.chhaon.whatsapp.MedicinePhotoStore;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for Supabase Storage: files live in a map, nothing goes over the network. Off by default (like production
 * without SUPABASE_URL), so every other test keeps getting plain text reminders.
 */
public class FakeMedicinePhotoStore extends MedicinePhotoStore {

    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private volatile boolean on;
    private volatile boolean broken;

    public FakeMedicinePhotoStore() {
        super("", "");
    }

    @Override
    public boolean isConfigured() {
        return on;
    }

    @Override
    protected byte[] download(String path) throws IOException {
        if (broken) throw new IOException("storage_http_500");
        return files.get(path);
    }

    public FakeMedicinePhotoStore switchOn(boolean on) {
        this.on = on;
        return this;
    }

    /** Storage answers with an error (500 / timeout). */
    public void breakStorage(boolean broken) {
        this.broken = broken;
    }

    public void put(String path, byte[] bytes) {
        files.put(path, bytes);
    }

    public void reset() {
        files.clear();
        on = false;
        broken = false;
    }
}
