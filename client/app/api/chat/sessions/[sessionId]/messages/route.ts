/**
 * SSE proxy route: POST /api/chat/sessions/[sessionId]/messages
 *
 * Next.js rewrites buffer the response body and therefore break SSE streams.
 * This custom App Router route uses the Fetch API to stream the Spring Boot
 * SSE response directly to the browser — no buffering, no ERR_INCOMPLETE_CHUNKED_ENCODING.
 */

import { NextRequest } from "next/server";
import { cookies } from "next/headers";

const BACKEND_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080";

export async function POST(
  req: NextRequest,
  { params }: { params: Promise<{ sessionId: string }> }
) {
  const { sessionId } = await params;

  // Forward all cookies (including DEV_SESSION) to the Spring Boot backend
  const cookieStore = await cookies();
  const cookieHeader = cookieStore
    .getAll()
    .map((c) => `${c.name}=${c.value}`)
    .join("; ");

  const body = await req.text();

  console.log(`[SSE Proxy] Forwarding chat stream for session ${sessionId} to ${BACKEND_URL}`);

  const backendRes = await fetch(
    `${BACKEND_URL}/api/chat/sessions/${sessionId}/messages`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(cookieHeader ? { Cookie: cookieHeader } : {}),
      },
      body,
      cache: "no-store",
      signal: req.signal,
    }
  );

  if (!backendRes.ok || !backendRes.body) {
    // Forward the error status from Spring Boot
    const errorBody = await backendRes.text();
    return new Response(errorBody, {
      status: backendRes.status,
      headers: { "Content-Type": "application/json" },
    });
  }

  // Stream the SSE response body directly — no buffering
  return new Response(backendRes.body, {
    status: 200,
    headers: {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no", // disable nginx buffering if behind nginx
    },
  });
}

