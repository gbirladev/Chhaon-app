/**
 * Chhaon: polls we send and the votes on them.
 *
 * - rememberSentPoll: every poll this gateway sends is kept (table SentPoll) with its full WAMessage, because a vote
 *   can only be decrypted with the poll's messageSecret. Kept 30 days, then deleted.
 * - getStoredMessage: the socket's `getMessage` (Baileys asks for it on retries; returns our stored polls).
 * - handlePollVote: a `pollUpdateMessage` from messages.upsert is decrypted and sent as a `poll.vote` webhook.
 *   Votes on polls we did not send (or cannot decrypt) are ignored with a debug log.
 */
import { proto, normalizeMessageContent, type WAMessage, type WAMessageKey } from "@whiskeysockets/baileys";
import { prisma } from "@/lib/prisma";
import { logger } from "@/lib/logger";
import { onPollVote } from "@/lib/webhook";
import { decryptVoteWithCandidates } from "@/lib/poll-decrypt";
import { mapVote, isOneToOneChat, jidCandidates, voteTimestamp } from "@/lib/poll";

const KEEP_DAYS = 30;
const CLEANUP_EVERY_MS = 60 * 60 * 1000;
let lastCleanup = 0;

/** The poll creation content (single choice = V3, multiple = V1, announcement groups = V2), or null. */
function pollCreationOf(message: proto.IMessage | null | undefined) {
    const content = normalizeMessageContent(message);
    return content?.pollCreationMessageV3 || content?.pollCreationMessage || content?.pollCreationMessageV2 || null;
}

/** Keeps a poll we sent. Safe to call twice for the same message (the API route and messages.upsert both do). */
export async function rememberSentPoll(sessionId: string, msg: WAMessage | null | undefined): Promise<void> {
    const keyId = msg?.key?.id;
    const remoteJid = msg?.key?.remoteJid;
    const poll = pollCreationOf(msg?.message);
    if (!keyId || !remoteJid || !poll) return;
    if (!msg?.message?.messageContextInfo?.messageSecret?.length) {
        logger.warn("Poll", `Poll ${keyId} has no messageSecret, votes on it cannot be decrypted`);
        return;
    }
    const options = (poll.options || []).map((o) => o.optionName || "");
    const encoded = Buffer.from(proto.WebMessageInfo.encode(msg as proto.IWebMessageInfo).finish());

    await prisma.sentPoll.upsert({
        where: { sessionId_keyId: { sessionId, keyId } },
        create: { sessionId, keyId, remoteJid, question: poll.name || "", options, message: encoded },
        update: {},
    });

    if (Date.now() - lastCleanup > CLEANUP_EVERY_MS) {
        lastCleanup = Date.now();
        const cutoff = new Date(Date.now() - KEEP_DAYS * 24 * 60 * 60 * 1000);
        prisma.sentPoll
            .deleteMany({ where: { createdAt: { lt: cutoff } } })
            .catch((e) => logger.error("Poll", "Failed to delete old polls", e));
    }
}

async function findSentPoll(sessionId: string, keyId: string) {
    const row = await prisma.sentPoll.findUnique({ where: { sessionId_keyId: { sessionId, keyId } } });
    if (!row) return null;
    return {
        options: (row.options as string[]) || [],
        message: proto.WebMessageInfo.decode(row.message),
    };
}

/** For makeWASocket({ getMessage }): the stored message content for a key, or undefined. */
export async function getStoredMessage(sessionId: string, key: WAMessageKey): Promise<proto.IMessage | undefined> {
    if (!key?.id) return undefined;
    try {
        const poll = await findSentPoll(sessionId, key.id);
        return poll?.message.message || undefined;
    } catch (e) {
        logger.error("Poll", "getMessage lookup failed", e);
        return undefined;
    }
}

/** True when this upsert message is a poll vote (the caller then stops: a vote is not stored as a chat message). */
export function isPollVote(msg: WAMessage): boolean {
    return !!normalizeMessageContent(msg.message)?.pollUpdateMessage;
}

/**
 * Decrypts a vote on one of our polls and sends `poll.vote`. `me` is sock.user (phone id and LID of our number).
 */
export async function handlePollVote(
    sessionId: string,
    msg: WAMessage,
    me: { id?: string | null; lid?: string | null } | undefined
): Promise<void> {
    const update = normalizeMessageContent(msg.message)?.pollUpdateMessage;
    const pollKeyId = update?.pollCreationMessageKey?.id;
    const key = msg.key;
    if (!update || !pollKeyId || !key || key.fromMe) return;
    if (!isOneToOneChat(key.remoteJid)) {
        logger.debug("Poll", `Ignoring a poll vote outside a one-to-one chat (poll ${pollKeyId})`);
        return;
    }

    const poll = await findSentPoll(sessionId, pollKeyId);
    const pollEncKey = poll?.message.message?.messageContextInfo?.messageSecret;
    if (!poll || !pollEncKey?.length) {
        logger.debug("Poll", `Ignoring a vote on poll ${pollKeyId}: not a poll this gateway sent (or older than ${KEEP_DAYS} days)`);
        return;
    }

    const k = key as WAMessageKey & { remoteJidAlt?: string | null; participantAlt?: string | null };
    const decrypted = decryptVoteWithCandidates(update.vote, {
        pollMsgId: pollKeyId,
        pollEncKey,
        creatorCandidates: jidCandidates(me?.id, me?.lid),
        voterCandidates: jidCandidates(k.remoteJidAlt, k.remoteJid, k.participantAlt, k.participant),
    });
    if (!decrypted) {
        logger.warn("Poll", `Could not decrypt a vote on our poll ${pollKeyId} (no phone/LID combination matched)`);
        return;
    }

    const { selectedOptions, selectedIndexes } = mapVote(poll.options, decrypted.selectedOptions);
    await onPollVote(sessionId, msg, {
        pollKeyId,
        selectedOptions,
        selectedIndexes,
        timestamp: voteTimestamp(update.senderTimestampMs, msg.messageTimestamp),
    });
}
