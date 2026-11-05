/**
 * Poll votes (Chhaon change, see docs/buddy-handoff.md "Deploy the gateway change: poll answers").
 *
 * A parent answers a medicine reminder by tapping an option of a WhatsApp poll WE sent. The vote reaches Baileys as
 * `messages.upsert` with `message.pollUpdateMessage = { pollCreationMessageKey, vote: { encPayload, encIv } }`.
 * Baileys 7.0.0-rc.9 does NOT decrypt it (the code in process-message.js is commented out), so the gateway does it
 * itself (src/modules/whatsapp/poll-votes.ts) with the poll's stored messageSecret and sends a `poll.vote` webhook.
 *
 * A decrypted vote holds the SHA-256 hashes of the voter's CURRENT selection (all of it, not a delta), so the last
 * vote is the answer; an empty list means the vote was removed.
 *
 * Pure functions only (no Prisma, no Baileys import), so they can be tested without a database.
 */
import { createHash } from "crypto";

export const POLL_VOTE_EVENT = "poll.vote";

/** Hex SHA-256 of an option text, the way WhatsApp hashes options in a vote. */
export function optionHash(option: string): string {
    return createHash("sha256").update(Buffer.from(option, "utf8")).digest("hex");
}

/**
 * Turns the decrypted vote's option hashes into option texts and indexes, in the order of the poll's options.
 * Unknown hashes are ignored; duplicates are reported once.
 */
export function mapVote(options: string[], selectedHashes: ArrayLike<number>[] | null | undefined): {
    selectedOptions: string[];
    selectedIndexes: number[];
} {
    const chosen = new Set((selectedHashes || []).map((h) => Buffer.from(h as Uint8Array).toString("hex")));
    const selectedOptions: string[] = [];
    const selectedIndexes: number[] = [];
    options.forEach((option, index) => {
        if (chosen.has(optionHash(option))) {
            selectedOptions.push(option);
            selectedIndexes.push(index);
        }
    });
    return { selectedOptions, selectedIndexes };
}

/** The `data` of the `poll.vote` webhook. */
export function pollVotePayload(input: {
    pollKeyId: string;
    from: string;
    remoteJid: string;
    selectedOptions: string[];
    selectedIndexes: number[];
    timestamp: number;
}) {
    return {
        pollKeyId: input.pollKeyId,
        from: input.from,
        remoteJid: input.remoteJid,
        selectedOptions: input.selectedOptions,
        selectedIndexes: input.selectedIndexes,
        timestamp: input.timestamp,
    };
}

/**
 * Whether a webhook with these subscribed events gets this event. `poll.vote` also goes to webhooks subscribed to
 * `message.received` (a vote IS the parent's answer), so the existing "Chhaon backend" webhook needs no change.
 */
export function webhookWantsEvent(events: string[] | null | undefined, event: string): boolean {
    const list = events || [];
    if (list.includes(event) || list.includes("*")) return true;
    return event === POLL_VOTE_EVENT && list.includes("message.received");
}

/** Only one-to-one chats: no groups, broadcast lists, status or channels. */
export function isOneToOneChat(jid: string | null | undefined): boolean {
    if (!jid) return false;
    return jid.endsWith("@s.whatsapp.net") || jid.endsWith("@lid") || jid.endsWith("@c.us");
}

/** "919999900001:12@s.whatsapp.net" -> "919999900001@s.whatsapp.net" (drops the device part). */
export function bareJid(jid: string | null | undefined): string | null {
    if (!jid || !jid.includes("@")) return null;
    const [user, server] = jid.split("@");
    const bareUser = user.split(":")[0];
    if (!bareUser) return null;
    return `${bareUser}@${server === "c.us" ? "s.whatsapp.net" : server}`;
}

/** Distinct bare jids, in order, without empty values. */
export function jidCandidates(...jids: (string | null | undefined)[]): string[] {
    const out: string[] = [];
    for (const jid of jids) {
        const bare = bareJid(jid);
        if (bare && !out.includes(bare)) out.push(bare);
    }
    return out;
}

/**
 * Unix seconds of a vote: the sender's timestamp in ms when present (number, string or protobuf Long), else the
 * message timestamp in seconds, else now.
 */
export function voteTimestamp(senderTimestampMs: unknown, messageTimestamp: unknown, nowMs = Date.now()): number {
    const ms = toNumber(senderTimestampMs);
    if (ms && ms > 0) return Math.floor(ms / 1000);
    const s = toNumber(messageTimestamp);
    if (s && s > 0) return Math.floor(s);
    return Math.floor(nowMs / 1000);
}

function toNumber(v: unknown): number | null {
    if (v === null || v === undefined) return null;
    if (typeof v === "number") return Number.isFinite(v) ? v : null;
    if (typeof v === "string") {
        const n = Number(v);
        return Number.isFinite(n) ? n : null;
    }
    if (typeof v === "bigint") return Number(v);
    if (typeof v === "object" && typeof (v as { toNumber?: unknown }).toNumber === "function") {
        return (v as { toNumber: () => number }).toNumber();
    }
    return null;
}
