import { ApiError } from "@/lib/api/client";
import { getLibraryHealth, verifyLibraryHealth } from "@/lib/api/health";

async function respond(scan: boolean) {
  try {
    return Response.json(await (scan ? verifyLibraryHealth() : getLibraryHealth()));
  } catch (error) {
    const status = error instanceof ApiError && error.status === 409 ? 409 : 502;
    return Response.json({ error: "Unable to access library health" }, { status });
  }
}

export const GET = () => respond(false);
export const POST = () => respond(true);
