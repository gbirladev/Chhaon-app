import makeWASocket, {
    DisconnectReason,
    fetchLatestBaileysVersion,
    makeCacheableSignalKeyStore,
    WASocket,
    ConnectionState
} from "@whiskeysockets/baileys";
import { prisma } from "@/lib/prisma";
import { usePrismaAuthState } from "./auth/usePrismaAuthState";
import { Server } from "socket.io";
import pino from "pino";
import { bindSessionStore } from "./store";
import { syncGroups } from "./store/groups";
import { bindContactSync } from "./store/contacts";
import { bindAutoReply } from "./store/autoreply";
import { bindPpGuard } from "./store/ppguard";
import { antispam } from "./antispam";
import { logger } from "@/lib/logger";

// 0 = retry forever (default). Set WA_MAX_RECONNECT_ATTEMPTS to cap it.
const MAX_RECONNECT_ATTEMPTS = parseInt(process.env.WA_MAX_RECONNECT_ATTEMPTS || "0", 10);
const RECONNECT_BASE_DELAY_MS = 2000;
const RECONNECT_MAX_DELAY_MS = parseInt(process.env.WA_RECONNECT_MAX_DELAY_MS || "60000", 10);

export class WhatsAppInstance {
    socket: WASocket | null = null;
    qr: string | null = null;
    rq: string | null = null;
    status: string = "DISCONNECTED";
    sessionId: string;
    userId: string;
    io: Server;
    config: any = {};
    startTime: Date | null = null;
    pairingCode: string | null = null;

    isStopped: boolean = false;
    private reconnectCount: number = 0;
    private reconnectTimer: NodeJS.Timeout | null = null;
    /** Called when instance auto-stops or logs out — lets manager remove it from Map */
    onRemovedFromManager: (() => void) | null = null;

    constructor(sessionId: string, userId: string, io: Server) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.io = io;
    }

    async init() {
        const sessionData = await prisma.session.findUnique({
            where: { sessionId: this.sessionId },
            include: { botConfig: true }
        });
        if (!sessionData) {
            logger.warn("Instance", `Session ${this.sessionId} not found in DB, aborting init`);
            return;
        }
        this.config = sessionData?.config || {};
        const botConfig = (sessionData as any)?.botConfig;

        const { state, saveCreds } = await usePrismaAuthState(this.sessionId);
        const { version } = await fetchLatestBaileysVersion();

        // Drop any previous socket so a stale one can't keep firing events
        // or hold a second connection that makes WhatsApp kick us (440).
        this.disposeSocket();

        const sock = makeWASocket({
            version,
            logger: pino({ level: process.env.BAILEYS_LOG_LEVEL || "error" }) as any,
            printQRInTerminal: false,
            auth: {
                creds: state.creds,
                keys: makeCacheableSignalKeyStore(state.keys, pino({ level: process.env.BAILEYS_LOG_LEVEL || "error" }) as any),
            },
            browser: ["Ubuntu", "Chrome", "20.0.04"],
            markOnlineOnConnect: botConfig?.alwaysOnline ?? true,
            syncFullHistory: process.env.WA_SYNC_FULL_HISTORY === "true",
        });
        this.socket = sock;

        // Apply Anti-Spam Wrapper to sendMessage
        const originalSendMessage = this.socket.sendMessage.bind(this.socket);
        const sessionId = this.sessionId;
        this.socket.sendMessage = async function (jid: string, content: any, options?: any) {
            await antispam.enqueue(sessionId, jid, content);
            return originalSendMessage(jid, content, options);
        } as any;

        // Bind Store for DB Sync
        bindSessionStore(this.socket, this.sessionId, this.io);

        // Bind Contact Sync
        bindContactSync(this.socket, this.sessionId);

        this.socket.ev.on("creds.update", saveCreds);

        this.socket.ev.on("connection.update", async (update) => {
            // Ignore events from a socket that has since been replaced
            if (sock !== this.socket) return;
            await this.handleConnectionUpdate(update);
        });
    }

    /** init() for callers outside the reconnect loop: a failed start (DB/network down) is retried instead of leaving the session dead */
    async start() {
        try {
            await this.init();
        } catch (e) {
            logger.error("Instance", `Session ${this.sessionId} failed to start, retrying:`, e);
            this.reconnectCount++;
            this.scheduleReconnect(this.backoffDelay());
        }
    }

    private disposeSocket() {
        if (!this.socket) return;
        try {
            this.socket.ev.removeAllListeners("connection.update");
            this.socket.ev.removeAllListeners("creds.update");
            this.socket.end(undefined);
        } catch (e) { /* already closed */ }
        this.socket = null;
    }

    private scheduleReconnect(delayMs: number) {
        if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
        this.reconnectTimer = setTimeout(async () => {
            this.reconnectTimer = null;
            if (this.isStopped) return;
            try {
                await this.init();
            } catch (e) {
                // init itself failed (network/DB down) - keep trying
                logger.error("Instance", `Session ${this.sessionId} reconnect init failed:`, e);
                this.reconnectCount++;
                this.scheduleReconnect(this.backoffDelay());
            }
        }, delayMs);
    }

    private backoffDelay() {
        const exp = RECONNECT_BASE_DELAY_MS * 2 ** Math.min(this.reconnectCount, 10);
        return Math.min(exp, RECONNECT_MAX_DELAY_MS);
    }

    async handleConnectionUpdate(update: Partial<ConnectionState>) {
        const { connection, lastDisconnect, qr } = update;

        try {
            if (qr) {
                if (this.isStopped) return;
                // Reset reconnect count on new QR (user is re-scanning)
                this.reconnectCount = 0;
                this.qr = qr;
                this.status = "SCAN_QR";

                this.io?.to(this.sessionId).emit("connection.update", { status: this.status, qr });

                await prisma.session.update({
                    where: { sessionId: this.sessionId },
                    data: { qr, status: "SCAN_QR" }
                });
            }

            if (connection === "close") {
                const code = (lastDisconnect?.error as any)?.output?.statusCode;
                const isLoggedOut = code === DisconnectReason.loggedOut;

                if (isLoggedOut) {
                    // Logged out: stop permanently, remove from memory
                    this.status = "LOGGED_OUT";
                    this.socket = null;
                    this.config = {};
                    this.io?.to(this.sessionId).emit("connection.update", { status: "LOGGED_OUT", qr: null });

                    logger.info("Instance", `Session ${this.sessionId} logged out. Deleting credentials...`);
                    try {
                        await prisma.$transaction([
                            prisma.session.update({
                                where: { sessionId: this.sessionId },
                                data: { status: "LOGGED_OUT", qr: null }
                            }),
                            prisma.authState.deleteMany({
                                where: { sessionId: this.sessionId }
                            })
                        ]);
                    } catch (e) { /* ignore */ }
                    logger.success("Instance", `Session ${this.sessionId} credentials deleted.`);

                    // Remove from memory manager
                    this.onRemovedFromManager?.();
                    return;
                }

                if (this.isStopped) {
                    // Explicitly stopped: preserve creds for restart
                    this.status = "STOPPED";
                    this.socket = null;
                    this.reconnectCount = 0;
                    this.io?.to(this.sessionId).emit("connection.update", { status: "STOPPED", qr: null });

                    await prisma.session.update({
                        where: { sessionId: this.sessionId },
                        data: { status: "STOPPED", qr: null }
                    }).catch(() => {});
                    logger.warn("Instance", `Session ${this.sessionId} stopped. Credentials preserved.`);

                    // Remove from memory manager
                    const { waManager } = require("./manager");
                    waManager.removeInstance(this.sessionId);
                    return;
                }

                // Baileys asks for an immediate restart after pairing / stream errors
                if (code === DisconnectReason.restartRequired) {
                    logger.info("Instance", `Session ${this.sessionId} restart required, reconnecting now...`);
                    this.scheduleReconnect(500);
                    return;
                }

                if (code === DisconnectReason.connectionReplaced) {
                    logger.warn("Instance",
                        `Session ${this.sessionId} replaced by another connection (440). ` +
                        `Make sure only ONE gateway instance uses these credentials.`
                    );
                }

                // Unexpected disconnect: retry with exponential backoff
                this.reconnectCount++;
                const exhausted = MAX_RECONNECT_ATTEMPTS > 0 && this.reconnectCount > MAX_RECONNECT_ATTEMPTS;

                if (!exhausted) {
                    this.status = "DISCONNECTED";
                    this.io?.to(this.sessionId).emit("connection.update", { status: "DISCONNECTED", qr: null });
                    await prisma.session.update({
                        where: { sessionId: this.sessionId },
                        data: { status: "DISCONNECTED", qr: null }
                    }).catch(() => {});

                    const delay = this.backoffDelay();
                    logger.warn("Instance",
                        `Session ${this.sessionId} disconnected (code ${code}). ` +
                        `Reconnect attempt ${this.reconnectCount}${MAX_RECONNECT_ATTEMPTS > 0 ? `/${MAX_RECONNECT_ATTEMPTS}` : ""} in ${delay}ms...`
                    );
                    this.scheduleReconnect(delay);
                } else {
                    // Max retries exceeded — auto-stop
                    this.status = "STOPPED";
                    this.socket = null;
                    this.reconnectCount = 0;
                    this.isStopped = true; // prevent further retries
                    this.io?.to(this.sessionId).emit("connection.update", { status: "STOPPED", qr: null });

                    await prisma.session.update({
                        where: { sessionId: this.sessionId },
                        data: { status: "STOPPED", qr: null }
                    }).catch(() => {});
                    logger.error("Instance",
                        `Session ${this.sessionId} max reconnects (${MAX_RECONNECT_ATTEMPTS}) reached. Auto-stopped.`
                    );

                    // Remove from memory manager
                    this.onRemovedFromManager?.();
                }
            }

            if (connection === "open") {
                // Connected — reset reconnect count
                this.reconnectCount = 0;
                this.isStopped = false;
                this.status = "CONNECTED";
                this.qr = null;
                this.startTime = new Date();

                this.io?.to(this.sessionId).emit("connection.update", { status: "CONNECTED", qr: null });

                try {
                    await syncGroups(this.socket as WASocket, this.sessionId);
                } catch (e) {
                    logger.error("Instance", "Group sync failed:", e);
                }

                bindAutoReply(this.socket as WASocket, this.sessionId);
                bindPpGuard(this.socket as WASocket, this.sessionId);

                await prisma.session.update({
                    where: { sessionId: this.sessionId },
                    data: { status: "CONNECTED", qr: null }
                });

                logger.success("Instance", `Session ${this.sessionId} connected and synced successfully`);
            }
        } catch (error: any) {
            if (error.code === 'P2025') {
                logger.warn("Instance", `Session ${this.sessionId} record not found during update. Stopping.`);
                this.socket?.end(undefined);
                this.socket = null;
            } else {
                logger.error("Instance", "Error in handleConnectionUpdate:", error);
            }
        }
    }

    async requestPairingCode(phoneNumber: string) {
        if (!this.socket) {
            throw new Error("Socket not initialized");
        }

        try {
            const cleanNumber = phoneNumber.replace(/[^0-9]/g, '');
            if (!cleanNumber) throw new Error("Invalid phone number");

            const code = await this.socket.requestPairingCode(cleanNumber);
            this.pairingCode = code;
            this.status = "SCAN_QR";

            this.io?.to(this.sessionId).emit("connection.update", {
                status: this.status,
                qr: this.qr,
                pairingCode: code
            });

            return code;
        } catch (error) {
            logger.error("Instance", "Pairing code error:", error);
            throw error;
        }
    }

    /** Clean shutdown without triggering reconnect */
    async shutdown() {
        this.isStopped = true;
        if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
        this.reconnectTimer = null;
        this.socket?.end(undefined);
        this.socket = null;
        this.reconnectCount = 0;
    }
}
