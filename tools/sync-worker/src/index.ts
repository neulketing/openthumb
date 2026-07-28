/**
 * OpenThumb sync worker — optional BYO-backend sync endpoint.
 *
 * Privacy stance, by design: no accounts, no multi-user, no analytics,
 * and no logging of payload contents. The single bearer token (SYNC_TOKEN)
 * is the entire auth model; whoever holds it holds the data. You deploy
 * this to your own Cloudflare account and nobody else operates it.
 */

export interface Env {
	SYNC_DB: D1Database;
	SYNC_BUCKET: R2Bucket;
	SYNC_TOKEN: string;
}

const KINDS = new Set(["chat", "trigger_run", "scheduled_task", "trigger_rule", "memory"]);
const INLINE_LIMIT = 64 * 1024; // payloads larger than this are offloaded to R2
const MAX_BATCH = 200;
const MAX_ID_LEN = 256;
const LIST_LIMIT = 1000;

interface SyncItem {
	kind: string;
	id: string;
	updatedAt: number;
	payload: unknown;
}

function json(body: unknown, status = 200): Response {
	return new Response(JSON.stringify(body), {
		status,
		headers: { "content-type": "application/json" },
	});
}

/** Compare tokens via SHA-256 digests to avoid a timing side channel. */
async function authorized(request: Request, env: Env): Promise<boolean> {
	const header = request.headers.get("Authorization") ?? "";
	if (!header.startsWith("Bearer ") || !env.SYNC_TOKEN) return false;
	const digest = async (s: string) =>
		new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)));
	const a = await digest(header.slice(7));
	const b = await digest(env.SYNC_TOKEN);
	if (a.length !== b.length) return false;
	let diff = 0;
	for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
	return diff === 0;
}

function r2Key(kind: string, id: string): string {
	return `${kind}/${id}`;
}

function validItem(raw: unknown): raw is SyncItem {
	if (typeof raw !== "object" || raw === null) return false;
	const item = raw as Record<string, unknown>;
	return (
		typeof item.kind === "string" &&
		KINDS.has(item.kind) &&
		typeof item.id === "string" &&
		item.id.length > 0 &&
		item.id.length <= MAX_ID_LEN &&
		typeof item.updatedAt === "number" &&
		Number.isFinite(item.updatedAt)
	);
}

async function handleBatch(request: Request, env: Env): Promise<Response> {
	let body: { items?: unknown };
	try {
		body = await request.json();
	} catch {
		return json({ error: "invalid JSON body" }, 400);
	}
	if (!Array.isArray(body.items) || body.items.length === 0) {
		return json({ error: "items must be a non-empty array" }, 400);
	}
	if (body.items.length > MAX_BATCH) {
		return json({ error: `batch too large (max ${MAX_BATCH})` }, 400);
	}
	for (const raw of body.items) {
		if (!validItem(raw)) return json({ error: "invalid item in batch" }, 400);
	}

	const encoder = new TextEncoder();
	const statements: D1PreparedStatement[] = [];
	for (const item of body.items as SyncItem[]) {
		const serialized = JSON.stringify(item.payload ?? null);
		const size = encoder.encode(serialized).byteLength;
		const offload = size > INLINE_LIMIT;
		if (offload) {
			await env.SYNC_BUCKET.put(r2Key(item.kind, item.id), serialized);
		}
		// Last write wins: an older batch never clobbers a newer row.
		statements.push(
			env.SYNC_DB.prepare(
				`INSERT INTO sync_items (kind, id, updated_at, size, offloaded, payload)
				 VALUES (?, ?, ?, ?, ?, ?)
				 ON CONFLICT (kind, id) DO UPDATE SET
				   updated_at = excluded.updated_at,
				   size = excluded.size,
				   offloaded = excluded.offloaded,
				   payload = excluded.payload
				 WHERE excluded.updated_at >= sync_items.updated_at`
			).bind(item.kind, item.id, Math.trunc(item.updatedAt), size, offload ? 1 : 0, offload ? null : serialized)
		);
	}
	await env.SYNC_DB.batch(statements);
	return json({ ok: true, accepted: body.items.length });
}

async function handleList(url: URL, env: Env): Promise<Response> {
	const kind = url.searchParams.get("kind") ?? "";
	if (!KINDS.has(kind)) return json({ error: "unknown or missing kind" }, 400);
	const since = Number(url.searchParams.get("since") ?? "0");
	if (!Number.isFinite(since)) return json({ error: "since must be a number" }, 400);

	const { results } = await env.SYNC_DB.prepare(
		`SELECT id, updated_at AS updatedAt FROM sync_items
		 WHERE kind = ? AND updated_at > ?
		 ORDER BY updated_at ASC
		 LIMIT ?`
	).bind(kind, Math.trunc(since), LIST_LIMIT).all();
	return json({ items: results ?? [] });
}

async function handleItem(url: URL, env: Env): Promise<Response> {
	const kind = url.searchParams.get("kind") ?? "";
	const id = url.searchParams.get("id") ?? "";
	if (!KINDS.has(kind)) return json({ error: "unknown or missing kind" }, 400);
	if (!id) return json({ error: "missing id" }, 400);

	const row = await env.SYNC_DB.prepare(
		`SELECT kind, id, updated_at AS updatedAt, offloaded, payload FROM sync_items
		 WHERE kind = ? AND id = ?`
	).bind(kind, id).first<{ kind: string; id: string; updatedAt: number; offloaded: number; payload: string | null }>();
	if (!row) return json({ error: "not found" }, 404);

	let payload: unknown = null;
	if (row.offloaded) {
		const object = await env.SYNC_BUCKET.get(r2Key(kind, id));
		if (!object) return json({ error: "payload missing from storage" }, 502);
		payload = JSON.parse(await object.text());
	} else {
		payload = JSON.parse(row.payload ?? "null");
	}
	return json({ kind: row.kind, id: row.id, updatedAt: row.updatedAt, payload });
}

export default {
	async fetch(request: Request, env: Env): Promise<Response> {
		try {
			if (!(await authorized(request, env))) return json({ error: "unauthorized" }, 401);
			const url = new URL(request.url);
			if (request.method === "POST" && url.pathname === "/sync/batch") return handleBatch(request, env);
			if (request.method === "GET" && url.pathname === "/sync/list") return handleList(url, env);
			if (request.method === "GET" && url.pathname === "/sync/item") return handleItem(url, env);
			return json({ error: "not found" }, 404);
		} catch {
			// Deliberately opaque: never log or echo request bodies or payload contents.
			return json({ error: "internal error" }, 500);
		}
	},
} satisfies ExportedHandler<Env>;
