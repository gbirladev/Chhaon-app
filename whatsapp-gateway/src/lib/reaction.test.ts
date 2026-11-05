// Run: npm test   (node:test through tsx; no database, no WhatsApp connection needed)
import { test } from "node:test";
import assert from "node:assert/strict";
import { extractReaction, reactionPayload } from "./reaction";

const reacted = { remoteJid: "919800000001@s.whatsapp.net", fromMe: true, id: "3EB0ABCDEF0123456789" };

test("a thumbs-up reaction carries the reacted message key", () => {
    const r = extractReaction({ reactionMessage: { key: reacted, text: "👍", senderTimestampMs: "1793779200000" } });
    assert.ok(r);
    assert.equal(r.text, "👍");
    assert.equal(r.removed, false);
    assert.equal(r.key.id, "3EB0ABCDEF0123456789");
    assert.equal(r.key.fromMe, true);
});

test("a removed reaction is reported as removed with an empty text", () => {
    const r = extractReaction({ reactionMessage: { key: reacted, text: "" } });
    assert.ok(r);
    assert.equal(r.removed, true);
    assert.equal(r.text, "");
});

test("a reaction inside a disappearing-message wrapper is found", () => {
    const r = extractReaction({ ephemeralMessage: { message: { reactionMessage: { key: reacted, text: "👍🏽" } } } });
    assert.equal(r?.text, "👍🏽");
    assert.equal(r?.key.id, reacted.id);
});

test("text messages, empty content and reactions without a target are not reactions", () => {
    assert.equal(extractReaction({ conversation: "1" }), null);
    assert.equal(extractReaction({ extendedTextMessage: { text: "👍" } }), null);
    assert.equal(extractReaction(null), null);
    assert.equal(extractReaction(undefined), null);
    assert.equal(extractReaction({ reactionMessage: { text: "👍" } }), null);
    assert.equal(extractReaction({ reactionMessage: { key: {}, text: "👍" } }), null);
});

test("the webhook payload has the text-message envelope plus type REACTION and the reacted key", () => {
    const reaction = extractReaction({ reactionMessage: { key: reacted, text: "👍" } })!;
    const p = reactionPayload({
        keyId: "REACTION-KEY-1",
        from: "919811111111@s.whatsapp.net",
        fromMe: false,
        isGroup: false,
        chatType: "PERSONAL",
        sender: "919811111111@s.whatsapp.net",
        receiver: "919800000001@s.whatsapp.net",
        pushName: "Papa",
        reaction,
    });
    assert.equal(p.type, "REACTION");
    assert.equal(p.content, "👍");
    assert.equal(p.key.id, "REACTION-KEY-1");
    assert.equal(p.key.remoteJid, "919811111111@s.whatsapp.net");
    assert.equal(p.key.fromMe, false);
    assert.equal(p.from, "919811111111@s.whatsapp.net");
    assert.equal(p.chatType, "PERSONAL");
    assert.equal(p.quoted, null);
    assert.deepEqual(p.reaction, { text: "👍", removed: false, key: { ...reacted, participant: null } });
    // JSON-safe (the webhook body is JSON.stringify'd)
    assert.doesNotThrow(() => JSON.stringify(p));
});
