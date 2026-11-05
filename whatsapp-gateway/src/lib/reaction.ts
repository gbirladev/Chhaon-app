/**
 * Emoji reactions (Chhaon change, see docs/java-whatsapp-replies.md "Reactions").
 *
 * A long-press + 👍 on one of our messages reaches Baileys as `messages.upsert` with
 * `message.reactionMessage = { key: <key of the message that was reacted to>, text: "👍" }`.
 * Removing a reaction is the same shape with `text: ""`. The store used to drop these as technical
 * messages; it now turns them into a `message.received` webhook with `type: "REACTION"`.
 *
 * Pure functions only (no Prisma, no Baileys import), so they can be tested without a database.
 */

export interface InboundReaction {
    /** The emoji, "" when the reaction was removed. */
    text: string;
    /** True when the sender removed their reaction. */
    removed: boolean;
    /** Key of the message that was reacted to (ours: the id our send returned). */
    key: {
        id: string;
        remoteJid: string | null;
        fromMe: boolean | null;
        participant: string | null;
    };
}

/** Wrappers WhatsApp may put around a message (disappearing messages, view-once, sent from another device). */
const WRAPPERS = [
    "ephemeralMessage",
    "viewOnceMessage",
    "viewOnceMessageV2",
    "viewOnceMessageV2Extension",
    "deviceSentMessage",
];

/** The reaction carried by a Baileys message content, or null when it is not a reaction (or has no target key). */
export function extractReaction(content: any): InboundReaction | null {
    let c = content;
    for (let depth = 0; c && typeof c === "object" && depth < 5; depth++) {
        const r = c.reactionMessage;
        if (r && typeof r === "object") {
            const id = r.key?.id;
            if (!id || typeof id !== "string") return null;
            const text = typeof r.text === "string" ? r.text : "";
            return {
                text,
                removed: text === "",
                key: {
                    id,
                    remoteJid: r.key?.remoteJid || null,
                    fromMe: typeof r.key?.fromMe === "boolean" ? r.key.fromMe : null,
                    participant: r.key?.participant || null,
                },
            };
        }
        const wrapper = WRAPPERS.find((w) => c[w]?.message);
        c = wrapper ? c[wrapper].message : null;
    }
    return null;
}

/**
 * The `data` of the `message.received` webhook for a reaction. Same envelope as a text message (key, from, sender,
 * isGroup, chatType), so a receiver that only reads `type == "TEXT"` ignores it safely.
 */
export function reactionPayload(input: {
    keyId: string;
    from: string;
    fromMe: boolean;
    isGroup: boolean;
    chatType: string;
    sender: any;
    participant?: any;
    receiver: string | null;
    pushName?: string | null;
    messageTimestamp?: any;
    reaction: InboundReaction;
}) {
    return {
        key: {
            id: input.keyId,
            remoteJid: input.from,
            fromMe: input.fromMe,
            participant: input.participant ?? null,
        },
        pushName: input.pushName ?? null,
        messageTimestamp: input.messageTimestamp ?? null,
        from: input.from,
        receiver: input.receiver,
        sender: input.sender,
        isGroup: input.isGroup,
        chatType: input.chatType,
        type: "REACTION",
        content: input.reaction.text,
        fileUrl: null,
        quoted: null,
        reaction: {
            text: input.reaction.text,
            removed: input.reaction.removed,
            key: input.reaction.key,
        },
    };
}
