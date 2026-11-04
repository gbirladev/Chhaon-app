package org.smartgurukul.chhaon.support;

import org.smartgurukul.chhaon.whatsapp.WhatsAppSender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Test double: records every message instead of sending it; can be told to fail. */
public class RecordingWhatsAppSender extends WhatsAppSender {

    public record Sent(String to, String text) {}

    private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger counter = new AtomicInteger();
    private volatile boolean failing;

    public RecordingWhatsAppSender() {
        super("", "", "test");
    }

    @Override
    public String send(String toPhone, String text) {
        if (failing) throw new IllegalStateException("simulated gateway failure");
        sent.add(new Sent(toPhone, text));
        return "test-out-" + counter.incrementAndGet();
    }

    public List<Sent> sent() {
        synchronized (sent) {
            return new ArrayList<>(sent);
        }
    }

    public List<Sent> sentTo(String phone) {
        return sent().stream().filter(s -> s.to().equals(phone)).toList();
    }

    public void failSends(boolean failing) {
        this.failing = failing;
    }

    public void reset() {
        sent.clear();
        failing = false;
    }
}
