/**
 * Decrypts one poll vote (Chhaon change). No database here, so it can be tested on its own.
 *
 * The vote key is derived from the poll's messageSecret plus the poll id, the poll creator's jid and the voter's jid.
 * Which jid form WhatsApp used (phone "@s.whatsapp.net" or "@lid") depends on how the chat is addressed, and Baileys
 * 7 mixes both, so every combination of the known forms is tried. AES-GCM rejects a wrong key, so a wrong guess can
 * never produce a wrong answer, only "not decrypted".
 */
import { decryptPollVote, type proto } from "@whiskeysockets/baileys";

export interface DecryptedVote {
    selectedOptions: Uint8Array[];
    creatorJid: string;
    voterJid: string;
}

export function decryptVoteWithCandidates(
    vote: { encPayload?: Uint8Array | null; encIv?: Uint8Array | null } | null | undefined,
    ctx: {
        pollMsgId: string;
        pollEncKey: Uint8Array;
        creatorCandidates: string[];
        voterCandidates: string[];
    }
): DecryptedVote | null {
    if (!vote?.encPayload || !vote?.encIv || !ctx.pollEncKey?.length) return null;
    for (const creatorJid of ctx.creatorCandidates) {
        for (const voterJid of ctx.voterCandidates) {
            try {
                const decrypted: proto.Message.PollVoteMessage = decryptPollVote(
                    { encPayload: vote.encPayload, encIv: vote.encIv },
                    { pollEncKey: ctx.pollEncKey, pollCreatorJid: creatorJid, pollMsgId: ctx.pollMsgId, voterJid }
                );
                return { selectedOptions: decrypted.selectedOptions || [], creatorJid, voterJid };
            } catch {
                // wrong jid combination (or not our poll): try the next one
            }
        }
    }
    return null;
}
