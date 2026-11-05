// Run: npm test   (node:test through tsx; no database, no WhatsApp connection needed)
import { test } from "node:test";
import assert from "node:assert/strict";
import { createCipheriv, createHash, createHmac, randomBytes } from "crypto";
import { proto } from "@whiskeysockets/baileys";
import {
    mapVote,
    optionHash,
    pollVotePayload,
    webhookWantsEvent,
    isOneToOneChat,
    bareJid,
    jidCandidates,
    voteTimestamp,
} from "./poll";
import { decryptVoteWithCandidates } from "./poll-decrypt";
import { resolveToPhoneJidBySessionId } from "./jid-utils";

// Fake numbers only.
const PARENT = "919999900001@s.whatsapp.net";
const PARENT_LID = "123456789012345@lid";
const GATEWAY = "919999900002@s.whatsapp.net";
const GATEWAY_LID = "987654321098765@lid";
const OPTIONS = ["✅ Taken", "⏰ Later"];
const hash = (s: string) => createHash("sha256").update(s).digest();

test("a vote for the first option maps to its text and index 0", () => {
    assert.deepEqual(mapVote(OPTIONS, [hash("✅ Taken")]), { selectedOptions: ["✅ Taken"], selectedIndexes: [0] });
});

test("a changed vote carries only the new choice", () => {
    assert.deepEqual(mapVote(OPTIONS, [hash("⏰ Later")]), { selectedOptions: ["⏰ Later"], selectedIndexes: [1] });
});

test("a removed vote (no hashes) gives empty arrays", () => {
    assert.deepEqual(mapVote(OPTIONS, []), { selectedOptions: [], selectedIndexes: [] });
    assert.deepEqual(mapVote(OPTIONS, null), { selectedOptions: [], selectedIndexes: [] });
});

test("unknown hashes are ignored and the result follows the poll's option order", () => {
    const r = mapVote(["A", "B", "C"], [hash("C"), hash("nope"), hash("A"), hash("A")]);
    assert.deepEqual(r, { selectedOptions: ["A", "C"], selectedIndexes: [0, 2] });
});

test("Hindi options hash like WhatsApp (SHA-256 of the UTF-8 text)", () => {
    assert.equal(optionHash("✅ ले ली"), createHash("sha256").update(Buffer.from("✅ ले ली", "utf8")).digest("hex"));
    assert.deepEqual(mapVote(["✅ ले ली", "⏰ थोड़ी देर में"], [hash("⏰ थोड़ी देर में")]).selectedIndexes, [1]);
});

test("the poll.vote payload has exactly the agreed fields", () => {
    const p = pollVotePayload({
        pollKeyId: "3EB0POLL0000000001",
        from: PARENT,
        remoteJid: PARENT,
        selectedOptions: ["✅ Taken"],
        selectedIndexes: [0],
        timestamp: 1793779200,
    });
    assert.deepEqual(p, {
        pollKeyId: "3EB0POLL0000000001",
        from: PARENT,
        remoteJid: PARENT,
        selectedOptions: ["✅ Taken"],
        selectedIndexes: [0],
        timestamp: 1793779200,
    });
});

test("poll.vote reaches webhooks subscribed to poll.vote, message.received or *", () => {
    assert.equal(webhookWantsEvent(["poll.vote"], "poll.vote"), true);
    assert.equal(webhookWantsEvent(["message.received", "message.status"], "poll.vote"), true);
    assert.equal(webhookWantsEvent(["*"], "poll.vote"), true);
    assert.equal(webhookWantsEvent(["message.status"], "poll.vote"), false);
    assert.equal(webhookWantsEvent(null, "poll.vote"), false);
    // other events keep the old rule
    assert.equal(webhookWantsEvent(["message.received"], "message.status"), false);
    assert.equal(webhookWantsEvent(["message.status"], "message.status"), true);
});

test("only one-to-one chats count", () => {
    assert.equal(isOneToOneChat(PARENT), true);
    assert.equal(isOneToOneChat(PARENT_LID), true);
    assert.equal(isOneToOneChat("120363000000000001@g.us"), false);
    assert.equal(isOneToOneChat("status@broadcast"), false);
    assert.equal(isOneToOneChat("120363000000000002@newsletter"), false);
    assert.equal(isOneToOneChat(""), false);
});

test("jids lose their device part and duplicates", () => {
    assert.equal(bareJid("919999900002:12@s.whatsapp.net"), GATEWAY);
    assert.equal(bareJid("987654321098765:3@lid"), GATEWAY_LID);
    assert.equal(bareJid("919999900001@c.us"), PARENT);
    assert.equal(bareJid(undefined), null);
    assert.deepEqual(jidCandidates(PARENT, null, "919999900001:4@s.whatsapp.net", PARENT_LID), [PARENT, PARENT_LID]);
});

test("vote time: sender ms (Long-like, string or number), else message seconds, else now", () => {
    assert.equal(voteTimestamp({ toNumber: () => 1793779200123 }, 1), 1793779200);
    assert.equal(voteTimestamp("1793779200999", null), 1793779200);
    assert.equal(voteTimestamp(null, 1793779201), 1793779201);
    assert.equal(voteTimestamp(undefined, undefined, 1793779202000), 1793779202);
});

test("the voter is reported as a phone jid even when WhatsApp addresses the chat by LID", async () => {
    // inline remoteJidAlt wins before any database lookup
    assert.equal(await resolveToPhoneJidBySessionId(PARENT_LID, "test-session", PARENT), PARENT);
    assert.equal(await resolveToPhoneJidBySessionId(PARENT, "test-session", PARENT_LID), PARENT);
});

/** Encrypts a vote the way WhatsApp does (mirror of Baileys' decryptPollVote). */
function encryptVote(selected: string[], c: { pollMsgId: string; pollEncKey: Buffer; creatorJid: string; voterJid: string }) {
    const sign = Buffer.concat([
        Buffer.from(c.pollMsgId),
        Buffer.from(c.creatorJid),
        Buffer.from(c.voterJid),
        Buffer.from("Poll Vote"),
        Buffer.from([1]),
    ]);
    const key0 = createHmac("sha256", Buffer.alloc(32)).update(c.pollEncKey).digest();
    const key = createHmac("sha256", key0).update(sign).digest();
    const iv = randomBytes(12);
    const cipher = createCipheriv("aes-256-gcm", key, iv);
    cipher.setAAD(Buffer.from(`${c.pollMsgId}\u0000${c.voterJid}`));
    const plain = proto.Message.PollVoteMessage.encode({ selectedOptions: selected.map(hash) }).finish();
    const enc = Buffer.concat([cipher.update(plain), cipher.final(), cipher.getAuthTag()]);
    return { encPayload: enc, encIv: iv };
}

test("a vote encrypted with LID jids is decrypted by trying phone and LID forms", () => {
    const pollEncKey = randomBytes(32);
    const pollMsgId = "3EB0POLL0000000002";
    const vote = encryptVote(["⏰ Later"], { pollMsgId, pollEncKey, creatorJid: GATEWAY_LID, voterJid: PARENT_LID });
    const r = decryptVoteWithCandidates(vote, {
        pollMsgId,
        pollEncKey,
        creatorCandidates: jidCandidates("919999900002:12@s.whatsapp.net", "987654321098765:12@lid"),
        voterCandidates: jidCandidates(PARENT, PARENT_LID),
    });
    assert.ok(r);
    assert.equal(r.creatorJid, GATEWAY_LID);
    assert.equal(r.voterJid, PARENT_LID);
    assert.deepEqual(mapVote(OPTIONS, r.selectedOptions), { selectedOptions: ["⏰ Later"], selectedIndexes: [1] });
});

test("a vote with phone jids decrypts; a wrong secret or an empty vote gives null, never a wrong answer", () => {
    const pollEncKey = randomBytes(32);
    const pollMsgId = "3EB0POLL0000000003";
    const vote = encryptVote(["✅ Taken"], { pollMsgId, pollEncKey, creatorJid: GATEWAY, voterJid: PARENT });
    const ctx = { pollMsgId, creatorCandidates: [GATEWAY, GATEWAY_LID], voterCandidates: [PARENT_LID, PARENT] };
    const ok = decryptVoteWithCandidates(vote, { ...ctx, pollEncKey });
    assert.deepEqual(mapVote(OPTIONS, ok?.selectedOptions).selectedOptions, ["✅ Taken"]);
    assert.equal(decryptVoteWithCandidates(vote, { ...ctx, pollEncKey: randomBytes(32) }), null);
    assert.equal(decryptVoteWithCandidates(null, { ...ctx, pollEncKey }), null);
    const removed = encryptVote([], { pollMsgId, pollEncKey, creatorJid: GATEWAY, voterJid: PARENT });
    assert.deepEqual(mapVote(OPTIONS, decryptVoteWithCandidates(removed, { ...ctx, pollEncKey })?.selectedOptions), {
        selectedOptions: [],
        selectedIndexes: [],
    });
});
