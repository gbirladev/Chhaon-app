import { prisma } from "@/lib/prisma";
import type { WASocket, WAMessage, Contact } from "@whiskeysockets/baileys";
import { normalizeMessageContent } from "@whiskeysockets/baileys";
import { onMessageReceived, onMessageSent, onReactionReceived, dispatchWebhook, downloadAndSaveMedia } from "@/lib/webhook";
import { extractReaction } from "@/lib/reaction";
import { isPollVote, handlePollVote, rememberSentPoll } from "../poll-votes";
import { handleBotCommand, setSessionStartTime } from "../bot/command-handler";
import { resolveToPhoneJid, isLidJid, normalizeJid } from "@/lib/jid-utils";

import { Server } from "socket.io";
import { logger } from "@/lib/logger";

export const bindSessionStore = (sock: WASocket, sessionId: string, io: Server | null) => {
    // Set start time for uptime command
    setSessionStartTime(sessionId);

    // First, get the database Session ID (cuid)
    let dbSessionId: string | null = null;

    // Initialize by fetching the session ID
    (async () => {
        const session = await prisma.session.findUnique({
            where: { sessionId },
            select: { id: true }
        });
        if (session) {
            dbSessionId = session.id;
            logger.info("Store", `Message store initialized for session ${sessionId} (db: ${dbSessionId})`);
        } else {
            logger.error("Store", `Session ${sessionId} not found for message store`);
        }
    })();

    // Handle Messages
    sock.ev.on('messages.upsert', async ({ messages, type }) => {
        // Process all message types: notify, append, and history sync
        if (type !== 'notify' && type !== 'append') {
            // For history sync, we still want to save messages
            logger.debug("Store", `Received ${messages.length} messages of type: ${type}`);
        }

        // Emit to socket room for real-time frontend updates
        if (type === 'notify' || type === 'append') {
            io?.to(sessionId).emit('message.upsert', { messages, type });
        }

        // Ensure we have the database session ID
        if (!dbSessionId) {
            const session = await prisma.session.findUnique({ where: { sessionId }, select: { id: true } });
            if (!session) return;
            dbSessionId = session.id;
        }

        const processedMessages = [];

        // Fetch Bot Config for auto-read & welcome message
        const config = await prisma.botConfig.findUnique({
            where: { sessionId: dbSessionId }
        });

        for (const msg of messages) {
            try {
                // Auto Read Logic
                if (type === 'notify' && (config as any)?.autoRead && !msg.key.fromMe) {
                    await sock.readMessages([msg.key]);
                }

                const savedMessage = await processAndSaveMessage(msg, dbSessionId, sessionId, type === 'notify', sock, config, io, type === 'notify' || type === 'append');
                if (savedMessage) {
                    processedMessages.push(savedMessage);
                }

                // Execute Bot Commands (Only for Notify / New Messages)
                if (type === 'notify' && savedMessage) {
                    // Run in background, don't await strictly to not block saving
                    handleBotCommand(sock, sessionId, msg).catch(e => logger.error("Bot", "Bot Handler Error", e));
                }
            } catch (error) {
                logger.error("Store", "Error saving message", error);
            }
        }

        // Emit to socket room for real-time frontend updates
        if (processedMessages.length > 0) {
            // Serialize for frontend: convert Date to ISO string
            const serialized = processedMessages.map((m: any) => ({
                ...m,
                timestamp: m.timestamp instanceof Date ? m.timestamp.toISOString() : m.timestamp
            }));
            logger.debug("Socket", `Emitting message.update for ${serialized.length} messages in session ${sessionId}`);
            io?.to(sessionId).emit('message.update', serialized);
        }
    });

    // Handle Message History Sync (when connecting for the first time or syncing)
    sock.ev.on('messaging-history.set', async ({ messages, chats, contacts, isLatest }) => {
        logger.info("Store", `History sync: ${messages?.length || 0} messages, ${chats?.length || 0} chats, ${contacts?.length || 0} contacts, latest: ${isLatest}`);

        // Ensure we have the database session ID
        if (!dbSessionId) {
            const session = await prisma.session.findUnique({ where: { sessionId }, select: { id: true } });
            if (!session) return;
            dbSessionId = session.id;
        }

        // Save all historical messages
        if (messages && messages.length > 0) {
            logger.info("Store", `Syncing ${messages.length} historical messages...`);
            for (const msg of messages) {
                try {
                    await processAndSaveMessage(msg, dbSessionId, sessionId, false, sock, undefined, io);
                } catch (error) {
                    logger.error("Store", "Error saving historical message", error);
                }
            }
            logger.success("Store", `Finished syncing ${messages.length} historical messages`);
        }


        // Note: Contacts and Chats are synced by src/modules/whatsapp/store/contacts.ts
        // We only handle messages here to avoid P2002 Unique Constraint Race Conditions.
        logger.debug("Store", `Finished syncing messages`);
    });

    // Handle Contacts Upsert
    sock.ev.on('contacts.upsert', async (contacts) => {
        // Ensure we have the database session ID
        if (!dbSessionId) {
            const session = await prisma.session.findUnique({ where: { sessionId }, select: { id: true } });
            if (!session) return;
            dbSessionId = session.id;
        }

        for (const c of contacts) {
            try {
                if (!c.id) continue;
                await prisma.contact.upsert({
                    where: { sessionId_jid: { sessionId: dbSessionId, jid: c.id } },
                    create: {
                        sessionId: dbSessionId,
                        jid: c.id,
                        // @ts-ignore
                        lid: c.lid || undefined,
                        name: c.name || c.notify || c.verifiedName,
                        notify: c.notify,
                        // @ts-ignore
                        verifiedName: c.verifiedName,
                        profilePic: c.imgUrl || undefined,
                        data: c as any
                    },
                    update: {
                        // @ts-ignore
                        lid: c.lid || undefined,
                        name: c.name || undefined,
                        notify: c.notify || undefined,
                        // @ts-ignore
                        verifiedName: c.verifiedName || undefined,
                        profilePic: c.imgUrl || undefined,
                        data: c as any
                    }
                });

                // Dispatch webhook for contact update
                dispatchWebhook(sessionId, "contact.update", { jid: c.id, name: c.name, notify: c.notify });
            } catch (e) {
                logger.error("Store", "Error saving contact", e);
            }
        }
    });

    // Handle Message Status Updates
    sock.ev.on('messages.update', async (updates) => {
        if (!dbSessionId) return;

        for (const update of updates) {
            try {
                const keyId = update.key?.id;
                if (!keyId) continue;

                // Chhaon: poll results without a delivery status are not a status change (votes are handled in
                // messages.upsert, see poll-votes.ts): don't mark the poll PENDING or send a bogus message.status.
                if (update.update?.pollUpdates && update.update?.status == null) continue;

                const statusMap: Record<number, string> = {
                    0: 'PENDING',
                    1: 'SENT',
                    2: 'DELIVERED',
                    3: 'READ',
                    4: 'READ', // Played
                };

                const status = statusMap[update.update?.status || 0] || 'PENDING';

                await prisma.message.updateMany({
                    where: { sessionId: dbSessionId, keyId },
                    data: { status: status as any }
                });

                // Dispatch webhook for message status update
                dispatchWebhook(sessionId, "message.status", {
                    keyId,
                    remoteJid: update.key?.remoteJid,
                    status
                });
            } catch (e) {
                logger.error("Store", "Error updating message status", e);
            }
        }
    });

    // Handle Group Updates
    sock.ev.on('groups.update', async (updates) => {
        if (!dbSessionId) return;

        for (const update of updates) {
            try {
                if (!update.id) continue;
                
                // Keep DB in sync
                const updateData: any = {};
                if (update.subject !== undefined) updateData.subject = update.subject;
                if (update.desc !== undefined) updateData.description = update.desc;
                if (update.restrict !== undefined) updateData.restrict = update.restrict;
                if (update.announce !== undefined) updateData.announce = update.announce;
                if (update.owner !== undefined) updateData.ownerJid = update.owner;
                if (update.linkedParent !== undefined) updateData.linkedParentJid = update.linkedParent;
                if (update.isCommunity !== undefined) updateData.isCommunity = update.isCommunity;

                if (Object.keys(updateData).length > 0) {
                    await prisma.group.updateMany({
                        where: { sessionId: dbSessionId, jid: update.id },
                        data: updateData
                    });
                }
                
                // Trigger webhook
                dispatchWebhook(sessionId, "group.update", {
                    jid: update.id,
                    ...update
                });
            } catch (e) {
                logger.error("Store", "Error in groups.update handling", e);
            }
        }
    });

    // Handle Group Participants Update
    sock.ev.on('group-participants.update', async (update) => {
        if (!dbSessionId || !update.id) return;

        try {
            // update.id is the group JID
            // update.participants is array of JIDs
            // update.action is 'add', 'remove', 'promote', 'demote'
            dispatchWebhook(sessionId, "group.participant", {
                jid: update.id,
                action: update.action,
                participants: update.participants
            });
            
            // To properly resync the group participants in DB, it's safer to re-fetch the entire group metadata
            // But we don't await strictly to not block the socket
            sock.groupMetadata(update.id).then(async (g) => {
                await prisma.group.updateMany({
                    where: { sessionId: dbSessionId as string, jid: update.id as string },
                    data: { participants: g.participants as any }
                });
            }).catch(e => {
                 logger.debug("Store", "Failed to refresh group participants metadata", e);
            });
        } catch (e) {
            logger.error("Store", "Error in group-participants.update handling", e);
        }
    });
};

async function processAndSaveMessage(
    msg: WAMessage,
    dbSessionId: string,
    sessionId: string,
    triggerWebhook: boolean,
    sock?: WASocket,
    config?: any,
    io?: Server | null,
    // Chhaon: a live upsert ('notify', or 'append' = our own sends and messages queued while we were offline), not history sync
    live: boolean = triggerWebhook
) {
    const keyId = msg.key.id;
    const remoteJid = msg.key.remoteJid;
    const fromMe = msg.key.fromMe;
    const pushName = msg.pushName;
    const timestamp = msg.messageTimestamp
        ? new Date((typeof msg.messageTimestamp === 'number' ? msg.messageTimestamp : Number(msg.messageTimestamp)) * 1000)
        : new Date();

    // Filter out Protocol & Empty Messages
    if (!msg.message) return false;
    if (!keyId || !remoteJid) return false;

    // Ignore specific technical message types
    const messageKeys = Object.keys(msg.message);

    // Check for message edit/revoke protocol messages FIRST
    if (messageKeys.includes('protocolMessage')) {
        const pMessage = msg.message.protocolMessage;
        const targetKeyId = pMessage?.key?.id;

        // Message Edit (Type 14)
        if (pMessage?.type === 14 && targetKeyId) {
            const editContent = pMessage?.editedMessage;
            if (editContent) {
                // Determine new text
                const normalizedEdit = normalizeMessageContent(editContent);
                let newText = "";
                if (normalizedEdit?.conversation) newText = normalizedEdit.conversation;
                else if (normalizedEdit?.extendedTextMessage?.text) newText = normalizedEdit.extendedTextMessage.text;

                // Update DB safely
                await prisma.message.updateMany({
                    where: { sessionId: dbSessionId, keyId: targetKeyId },
                    data: { content: newText } // Note: we don't update timestamp of original
                });

                // Trigger Webhook
                if (triggerWebhook) {
                    dispatchWebhook(sessionId, "message.edited", {
                        keyId: targetKeyId,
                        newContent: newText,
                        remoteJid,
                        fromMe
                    });
                }
            }
            return null; // Stop processing as new message
        }

        // Message Revoke/Deleted (Type 0)
        if (pMessage?.type === 0 && targetKeyId) {
            // Check anti-delete config
            let antiDeleteEnabled = false;
            try {
                const s = await prisma.session.findUnique({
                    where: { sessionId },
                    select: { config: true }
                });
                antiDeleteEnabled = !!(s?.config as any)?.antiDelete;
            } catch {}

            if (antiDeleteEnabled) {
                // Anti-delete: read original, keep content, append marker
                const orig = await prisma.message.findFirst({
                    where: { sessionId: dbSessionId, keyId: targetKeyId },
                    select: { content: true }
                });
                const preserved = (orig?.content || "") + "\n\n🛡️ [Deleted by sender — preserved]";
                await prisma.message.updateMany({
                    where: { sessionId: dbSessionId, keyId: targetKeyId },
                    data: { content: preserved }
                });
                // Re-fetch & re-emit so frontend updates
                const updated = await prisma.message.findFirst({
                    where: { sessionId: dbSessionId, keyId: targetKeyId }
                });
                if (updated) {
                    io?.to(sessionId).emit("message.update", [{
                        ...updated,
                        timestamp: updated.timestamp instanceof Date ? updated.timestamp.toISOString() : updated.timestamp
                    }]);
                }
            } else {
                // Normal: mark as deleted
                await prisma.message.updateMany({
                    where: { sessionId: dbSessionId, keyId: targetKeyId },
                    data: { content: "[This message was deleted]", status: "FAILED" }
                });
            }

            // Trigger Webhook
            if (triggerWebhook) {
                dispatchWebhook(sessionId, "message.deleted", {
                    keyId: targetKeyId,
                    remoteJid,
                    fromMe
                });
            }
            return null;
        }
    }

    // Chhaon: a vote on a poll (tap on "✅ Taken"). Not stored as a chat message; a NEW vote from someone else on a poll
    // we sent is decrypted and forwarded as poll.vote (poll-votes.ts), also one that was queued while the gateway was
    // offline ('append'). History sync and our own votes are never forwarded.
    if (isPollVote(msg)) {
        if (live && !fromMe) {
            handlePollVote(sessionId, msg, sock?.user).catch(e => logger.error("Webhook", "Error in handlePollVote", e));
        }
        return null;
    }

    // Chhaon: keep every poll we send (also those sent from the dashboard; our sends arrive as 'append'), so votes on
    // it can be decrypted.
    if (fromMe && live) {
        rememberSentPoll(sessionId, msg).catch(e => logger.error("Poll", "Failed to keep a sent poll", e));
    }

    // Chhaon: an emoji reaction to a message (long-press + 👍). Not stored as a message (no text of its own, and the
    // MessageType enum has no REACTION); a NEW one from someone else is forwarded as message.received type "REACTION"
    // with the reacted message's key. History sync and our own reactions are never forwarded.
    const reaction = extractReaction(msg.message);
    if (reaction) {
        if (triggerWebhook && !fromMe) {
            onReactionReceived(sessionId, msg, reaction).catch(e => logger.error("Webhook", "Error in onReactionReceived", e));
        }
        return null;
    }

    const ignoredTypes = [
        'protocolMessage',
        'senderKeyDistributionMessage',
        'reactionMessage', // Optional: User might want reactions, but usually "kosong" means junk
        'keepInChatMessage'
    ];

    // If message only contains ignored types, skip (since edit and revoke handled above)
    if (messageKeys.every(k => ignoredTypes.includes(k))) {
        logger.debug("Store", `Skipping technical message: ${keyId} (${messageKeys.join(', ')})`);
        return null;
    }

    // Check if message already exists to avoid duplicates
    // Baileys 'notify' event can sometimes trigger multiple times or for history
    // Baileys 'notify' event can sometimes trigger multiple times or for history
    const existingMessage = await prisma.message.findUnique({
        where: { sessionId_keyId: { sessionId: dbSessionId, keyId: keyId! } },
        select: { id: true, status: true }
    });

    if (existingMessage) {
        // Message exists! Update status if changed, but DO NOT re-trigger webhooks/bot
        if (fromMe && existingMessage.status !== 'SENT') {
            await prisma.message.update({
                where: { id: existingMessage.id },
                data: { status: 'SENT' }
            });
        }
        // Return null to indicate "Not New"
        return null;
    }

    // Debug fromMe issue (Keep this for a while)
    if (fromMe === undefined || fromMe === null) {
        logger.warn("Store", `[DEBUG] Message ${keyId} has fromMe=${fromMe}. Key:`, msg.key);
    }

    const messageContent = normalizeMessageContent(msg.message);
    let text = "";
    let messageType = "TEXT";

    // Extract content based on message type
    if (messageContent?.conversation) {
        text = messageContent.conversation;
    } else if (messageContent?.extendedTextMessage?.text) {
        text = messageContent.extendedTextMessage.text;
    } else if (messageContent?.imageMessage) {
        messageType = "IMAGE";
        text = messageContent.imageMessage.caption || "";
    } else if (messageContent?.videoMessage) {
        messageType = "VIDEO";
        text = messageContent.videoMessage.caption || "";
    } else if (messageContent?.audioMessage) {
        messageType = "AUDIO";
    } else if (messageContent?.documentMessage) {
        messageType = "DOCUMENT";
        text = messageContent.documentMessage.fileName || "";
    } else if (messageContent?.stickerMessage) {
        messageType = "STICKER";
    } else if (messageContent?.locationMessage) {
        messageType = "LOCATION";
        text = `${messageContent.locationMessage.degreesLatitude},${messageContent.locationMessage.degreesLongitude}`;
    } else if (messageContent?.contactMessage) {
        messageType = "CONTACT";
        text = messageContent.contactMessage.displayName || "";
    }

    // Determine effective participant for groups
    // Determine effective participant for groups with standard logic
    const isGroup = remoteJid.endsWith("@g.us");
    const remoteJidAlt = msg.key.remoteJidAlt; // LID/Phone JID handling
    let senderJid = fromMe ? undefined : (isGroup ? (msg.key.participant || msg.participant) : remoteJid);

    // Prefer remoteJidAlt for DMs if available (matches webhook logic)
    if (!fromMe && !isGroup && remoteJidAlt) {
        senderJid = remoteJidAlt;
    }

    // --- Normalize LID JIDs to @s.whatsapp.net ---
    let normalizedRemoteJid = remoteJid;
    if (isLidJid(remoteJid)) {
        normalizedRemoteJid = await resolveToPhoneJid(remoteJid, dbSessionId, remoteJidAlt);
    }
    if (senderJid && isLidJid(senderJid)) {
        senderJid = await resolveToPhoneJid(senderJid, dbSessionId, remoteJidAlt);
    }


    // Download Media First (to save URL to DB)
    let fileUrl: string | null = null;
    try {
        fileUrl = await downloadAndSaveMedia(msg, sessionId);
    } catch (e) {
        logger.error("Store", "Error downloading media in store", e);
    }

    // Extract contextInfo (quoted message ID)
    let quoteId: string | null = null;
    if (msg.message) {
        const msgObj = msg.message as any;
        for (const key of Object.keys(msgObj)) {
            const inner = msgObj[key];
            if (inner && typeof inner === 'object' && inner !== null && 'contextInfo' in inner) {
                const contextInfo = (inner as any).contextInfo;
                if (contextInfo?.stanzaId) {
                    quoteId = contextInfo.stanzaId;
                    break;
                }
            }
        }
    }

    try {
        const newMessage = await prisma.message.create({
            data: {
                sessionId: dbSessionId,
                remoteJid: normalizeJid(normalizedRemoteJid),
                senderJid,
                fromMe: fromMe || false,
                keyId,
                pushName,
                type: messageType as any,
                content: text,
                mediaUrl: fileUrl, // Save Media URL
                status: fromMe ? "SENT" : "PENDING",
                timestamp,
                quoteId
            }
        });
        
        // Ensure contact exists (Upsert Contact)
        const finalRemoteJid = normalizeJid(normalizedRemoteJid);
        if (remoteJid && !remoteJid.includes('@g.us') && !remoteJid.includes('status@broadcast')) {
            const contactJid = finalRemoteJid; // Use fully normalized JID
            const contactData: any = {
                sessionId: dbSessionId,
                jid: contactJid
            };

            // Only update name/notify if message is FROM the contact (not from me)
            if (!fromMe) {
                if (pushName) contactData.notify = pushName;
                if (pushName) contactData.name = pushName;
            }

            const contact = await prisma.contact.upsert({
                where: { sessionId_jid: { sessionId: dbSessionId, jid: contactJid } },
                create: {
                    sessionId: dbSessionId,
                    jid: contactJid,
                    notify: !fromMe ? pushName : undefined,
                    name: !fromMe ? pushName : undefined,
                    // @ts-ignore
                    remoteJidAlt: remoteJidAlt || undefined
                },
                update: !fromMe ? {
                    notify: pushName,
                    // @ts-ignore
                    remoteJidAlt: remoteJidAlt || undefined
                } : {}
            });

            // Welcome Message Logic
            if (!fromMe && triggerWebhook && config?.welcomeMessage && sock) {
                // Let's check if message count for this contact is exactly 1 (the one we just saved)
                const msgCount = await prisma.message.count({
                    where: { sessionId: dbSessionId, remoteJid: finalRemoteJid }
                });

                if (msgCount === 1) {
                    logger.info("Store", `Sending welcome message to ${finalRemoteJid}`);
                    await sock.sendMessage(finalRemoteJid, { text: config.welcomeMessage });
                }
            }
        }

        // Trigger webhook for new messages only (not history sync)
        if (triggerWebhook) {
            if (fromMe) {
                onMessageSent(sessionId, msg, fileUrl).catch(e => logger.error("Webhook", "Error in onMessageSent", e));
            } else {
                onMessageReceived(sessionId, msg, fileUrl).catch(e => logger.error("Webhook", "Error in onMessageReceived", e));
            }
        }

        return newMessage;
    } catch (e: any) {
        logger.error("Store", "Error saving message query", e);
        throw e;
    }
}
// Placeholder - verified that I need to find the logic first
