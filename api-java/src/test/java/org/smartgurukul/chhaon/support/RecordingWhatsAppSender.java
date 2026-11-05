package org.smartgurukul.chhaon.support;

import org.smartgurukul.chhaon.whatsapp.WhatsAppSender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double: records every message instead of sending it; can be told to fail. An image (medicine photo reminder) is
 * recorded in {@link #images()} AND, with its caption as the text, in {@link #sent()}, so text checks keep working.
 */
public class RecordingWhatsAppSender extends WhatsAppSender {

    public record Sent(String to, String text) {}

    public record Image(String to, String caption, byte[] jpeg, String messageId) {}

    public record Poll(String to, String question, List<String> options, String messageId) {}

    private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
    private final List<Image> images = Collections.synchronizedList(new ArrayList<>());
    private final List<Poll> polls = Collections.synchronizedList(new ArrayList<>());
    /** ok | fail (the gateway refuses the poll) | no-id (an old gateway: delivered, but no messageId in the answer) */
    private volatile String pollMode = "ok";
    private final AtomicInteger counter = new AtomicInteger();
    private volatile boolean failing;
    private volatile boolean failingMedia;

    public RecordingWhatsAppSender() {
        super("", "", "test");
    }

    @Override
    public String send(String toPhone, String text) {
        if (failing) throw new IllegalStateException("simulated gateway failure");
        sent.add(new Sent(toPhone, text));
        return "test-out-" + counter.incrementAndGet();
    }

    @Override
    public String sendImage(String toPhone, byte[] jpeg, String caption) {
        if (failing || failingMedia) throw new IllegalStateException("WhatsApp gateway send failed: Failed to send media");
        String id = "test-img-" + counter.incrementAndGet();
        images.add(new Image(toPhone, caption, jpeg, id));
        sent.add(new Sent(toPhone, caption));
        return id;
    }

    /** A poll is recorded in {@link #polls()} only (not in {@link #sent()}, which holds texts and image captions). */
    @Override
    public String sendPoll(String toPhone, String question, List<String> options) {
        if (failing || "fail".equals(pollMode)) throw new IllegalStateException("WhatsApp gateway send failed: Failed to send poll");
        String id = "no-id".equals(pollMode) ? null : "test-poll-" + counter.incrementAndGet();
        polls.add(new Poll(toPhone, question, List.copyOf(options), id));
        return id;
    }

    public List<Poll> polls() {
        synchronized (polls) {
            return new ArrayList<>(polls);
        }
    }

    public List<Poll> pollsTo(String phone) {
        return polls().stream().filter(p -> p.to().equals(phone)).toList();
    }

    public void pollMode(String mode) {
        this.pollMode = mode;
    }

    public List<Sent> sent() {
        synchronized (sent) {
            return new ArrayList<>(sent);
        }
    }

    public List<Sent> sentTo(String phone) {
        return sent().stream().filter(s -> s.to().equals(phone)).toList();
    }

    public List<Image> images() {
        synchronized (images) {
            return new ArrayList<>(images);
        }
    }

    public void failSends(boolean failing) {
        this.failing = failing;
    }

    /** Only image sends fail (the gateway refuses media); plain text still works. */
    public void failMedia(boolean failingMedia) {
        this.failingMedia = failingMedia;
    }

    public void reset() {
        sent.clear();
        images.clear();
        polls.clear();
        pollMode = "ok";
        setPollsEnabled(false);
        counter.set(0);
        failing = false;
        failingMedia = false;
    }
}
