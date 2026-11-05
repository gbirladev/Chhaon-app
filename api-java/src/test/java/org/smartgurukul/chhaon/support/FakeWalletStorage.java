package org.smartgurukul.chhaon.support;

import org.smartgurukul.chhaon.wallet.WalletStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test double for the wallet's Storage calls: nothing goes over the network; records what was signed and removed. On by default. */
public class FakeWalletStorage extends WalletStorage {

    private volatile boolean on = true;
    public final List<String> uploads = Collections.synchronizedList(new ArrayList<>());
    public final List<String> removed = Collections.synchronizedList(new ArrayList<>());

    public FakeWalletStorage() {
        super("", "");
    }

    @Override
    public boolean isConfigured() {
        return on;
    }

    @Override
    public String uploadUrl(String path) {
        uploads.add(path);
        return "/object/upload/sign/" + BUCKET + "/" + path + "?token=fake";
    }

    @Override
    public Map<String, String> viewUrls(List<String> paths, int seconds) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String p : paths) out.put(p, "/object/sign/" + BUCKET + "/" + p + "?token=fake");
        return out;
    }

    @Override
    public void remove(List<String> paths) {
        removed.addAll(paths);
    }

    public FakeWalletStorage switchOn(boolean on) {
        this.on = on;
        return this;
    }

    public void reset() {
        on = true;
        uploads.clear();
        removed.clear();
    }
}
