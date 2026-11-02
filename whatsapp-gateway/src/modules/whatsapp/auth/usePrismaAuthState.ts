import { prisma } from "@/lib/prisma";
import { AuthenticationCreds, AuthenticationState, BufferJSON, initAuthCreds, SignalDataTypeMap } from "@whiskeysockets/baileys";
import { logger } from "@/lib/logger";

// Supabase is remote and the pool is small - retry transient failures (pool timeout,
// connection reset) instead of silently dropping a Signal key or the creds.
const DB_RETRIES = 3;
// Baileys can hand us hundreds of keys at once; firing them all in parallel
// exhausts the Prisma pool and times out the socket's own queries.
const DB_CONCURRENCY = 5;

const withRetry = async <T>(label: string, fn: () => Promise<T>): Promise<T> => {
    let lastError: unknown;
    for (let attempt = 1; attempt <= DB_RETRIES; attempt++) {
        try {
            return await fn();
        } catch (error) {
            lastError = error;
            if (attempt < DB_RETRIES) {
                await new Promise(resolve => setTimeout(resolve, 500 * attempt));
            }
        }
    }
    logger.error("Auth", `${label} failed after ${DB_RETRIES} attempts:`, lastError);
    throw lastError;
};

const runLimited = async (tasks: (() => Promise<void>)[], limit: number) => {
    for (let i = 0; i < tasks.length; i += limit) {
        await Promise.all(tasks.slice(i, i + limit).map(task => task()));
    }
};

export const usePrismaAuthState = async (sessionId: string): Promise<{ state: AuthenticationState, saveCreds: () => Promise<void> }> => {
    
    // Helper to read JSON with Buffer handling. Returns null only when the key
    // truly doesn't exist - a DB error throws, so a failed read of the creds can
    // never be mistaken for "no creds yet" and silently replaced by fresh ones.
    const readData = async (type: string, id: string) => {
        const key = `${type}-${id}`;
        const data = await withRetry(`Reading auth state ${key}`, () =>
            prisma.authState.findUnique({
                where: { sessionId_key: { sessionId, key } }
            })
        );
        if (data && data.value) {
            return JSON.parse(JSON.stringify(data.value), BufferJSON.reviver);
        }
        return null;
    };

    // Helper to write data
    const writeData = async (type: string, id: string, data: any) => {
        const key = `${type}-${id}`;
        const value = JSON.parse(JSON.stringify(data, BufferJSON.replacer));
        try {
            await withRetry(`Writing auth state ${key}`, () =>
                prisma.authState.upsert({
                    where: { sessionId_key: { sessionId, key } },
                    create: { sessionId, key, value },
                    update: { value }
                })
            );
        } catch (error) {
            // Already logged by withRetry; don't crash the socket's event handler
        }
    };

    const removeData = async (type: string, id: string) => {
        try {
            const key = `${type}-${id}`;
             await prisma.authState.deleteMany({
                where: { sessionId, key }
            });
        } catch (error) {
            // ignore
        }
    }


    const creds: AuthenticationCreds = (await readData('creds', 'me')) || initAuthCreds();

    return {
        state: {
            creds,
            keys: {
                get: async (type, ids) => {
                    const data: { [key: string]: SignalDataTypeMap[typeof type] } = {};
                    await runLimited(ids.map(id => async () => {
                        let value = await readData(type, id);
                        if (type === 'app-state-sync-key' && value) {
                            value = BufferJSON.reviver(null, value);
                        }
                        if (value) {
                            data[id] = value;
                        }
                    }), DB_CONCURRENCY);
                    return data;
                },
                set: async (data) => {
                    const tasks: (() => Promise<void>)[] = [];
                    for (const category in data) {
                        const categoryData = data[category as keyof typeof data];
                        if (!categoryData) continue;
                        
                        for (const id in categoryData) {
                            const value = categoryData[id];
                             if (value) {
                                tasks.push(() => writeData(category, id, value));
                            } else {
                                tasks.push(() => removeData(category, id));
                            }
                        }
                    }
                    await runLimited(tasks, DB_CONCURRENCY);
                }
            }
        },
        saveCreds: async () => {
            await writeData('creds', 'me', creds);
        }
    }
}
