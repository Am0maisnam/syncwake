import { DurableObject } from "cloudflare:workers";

/**
 * One instance per shared alarm. Holds the participants' live WebSocket connections (using the
 * hibernation API, so idle sockets cost nothing) and fans out status updates. Purely a
 * convenience for open apps: alarms never depend on it.
 */
export class AlarmRoom extends DurableObject {
  override async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/broadcast" && request.method === "POST") {
      const payload = await request.text();
      for (const ws of this.ctx.getWebSockets()) {
        try {
          ws.send(payload);
        } catch {
          // Closed socket; the runtime will clean it up.
        }
      }
      return new Response(null, { status: 204 });
    }
    if (request.headers.get("Upgrade") !== "websocket") return new Response("expected websocket", { status: 426 });
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1]);
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  override async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (message === "ping") ws.send("pong");
  }

  override async webSocketClose(ws: WebSocket, code: number): Promise<void> {
    // 1005 (no status) and 1006 (abnormal) are reserved and can't be sent back.
    const reply = code === 1005 || code === 1006 ? 1000 : code;
    try {
      ws.close(reply, "closing");
    } catch {
      // Already closed.
    }
  }
}
