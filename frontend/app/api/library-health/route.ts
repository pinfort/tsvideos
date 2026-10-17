import { ApiError } from "@/lib/api/client";
import { getLibraryHealth, verifyLibraryHealth } from "@/lib/api/health";

async function respond(scan: boolean) {
  try {
    return Response.json(await (scan ? verifyLibraryHealth() : getLibraryHealth()));
  } catch (error) {
    // 409: another scan is active. 502: the backend answered with an error. 504: no answer (unreachable or
    // timed out) — a scan may still be running on the backend.
    const status = !(error instanceof ApiError) ? 504 : error.status === 409 ? 409 : 502;
    return Response.json({ error: "Unable to access library health" }, { status });
  }
}

/** Browsers always send Origin on cross-origin POSTs; reject those so another site cannot start a NAS scan. */
function isCrossOrigin(request: Request): boolean {
  const origin = request.headers.get("origin");
  if (origin === null) return false;
  const host = request.headers.get("x-forwarded-host") ?? request.headers.get("host");
  try {
    return new URL(origin).host !== host;
  } catch {
    return true;
  }
}

export const GET = () => respond(false);
export const POST = (request: Request) =>
  isCrossOrigin(request)
    ? Response.json({ error: "Cross-origin request rejected" }, { status: 403 })
    : respond(true);
