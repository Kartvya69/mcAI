import WebSocket from "ws";

import type { FleetServerConfig } from "./config.js";

export type JsonObject = Record<string, unknown>;

export interface McAiFleetClientOptions {
  requestTimeoutMillis: number;
}

type PendingRequest = {
  resolve: (value: JsonObject) => void;
  reject: (error: Error) => void;
  timeout: NodeJS.Timeout;
};

export class FleetToolError extends Error {
  constructor(
    public readonly type: string,
    message: string,
    public readonly serverId: string,
    public readonly tool: string,
  ) {
    super(message);
    this.name = "FleetToolError";
  }
}

export class McAiFleetClient {
  private socket?: WebSocket;
  private connecting?: Promise<WebSocket>;
  private nextRequestId = 1;
  private readonly pending = new Map<string, PendingRequest>();

  constructor(
    private readonly server: FleetServerConfig,
    private readonly options: McAiFleetClientOptions,
  ) {}

  get isConnected(): boolean {
    return this.socket?.readyState === WebSocket.OPEN;
  }

  async probeConnection(): Promise<boolean> {
    try {
      await this.connect();
      return true;
    } catch {
      return false;
    }
  }

  async callTool(tool: string, args: JsonObject): Promise<JsonObject> {
    const socket = await this.connect();
    const id = String(this.nextRequestId++);
    const payload = JSON.stringify({ id, tool, arguments: args });

    return new Promise<JsonObject>((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`Server ${this.server.id} tool ${tool} timed out after ${this.options.requestTimeoutMillis}ms`));
      }, this.options.requestTimeoutMillis);

      this.pending.set(id, { resolve, reject, timeout });
      socket.send(payload, (error) => {
        if (!error) return;
        clearTimeout(timeout);
        this.pending.delete(id);
        reject(new Error(`Server ${this.server.id} websocket send failed: ${error.message}`));
      });
    });
  }

  close(): void {
    this.connecting = undefined;
    this.socket?.close();
    this.socket = undefined;
    this.rejectPending(new Error(`Server ${this.server.id} connection closed`));
  }

  private connect(): Promise<WebSocket> {
    if (this.socket?.readyState === WebSocket.OPEN) {
      return Promise.resolve(this.socket);
    }
    if (this.connecting) {
      return this.connecting;
    }

    this.connecting = new Promise<WebSocket>((resolve, reject) => {
      const socket = new WebSocket(this.server.url, {
        headers: {
          Authorization: `Bearer ${this.server.token}`,
        },
      });
      let settled = false;
      const timeout = setTimeout(() => {
        socket.terminate();
        finish(new Error(`Server ${this.server.id} offline or connection timed out`));
      }, this.options.requestTimeoutMillis);

      const finish = (error?: Error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timeout);
        socket.off("open", handleOpen);
        socket.off("error", handleError);
        socket.off("unexpected-response", handleUnexpectedResponse);
        if (error) {
          this.connecting = undefined;
          reject(error);
        } else {
          this.socket = socket;
          this.connecting = undefined;
          resolve(socket);
        }
      };

      const handleOpen = () => finish();
      const handleError = (error: Error) => finish(new Error(`Server ${this.server.id} offline: ${error.message}`));
      const handleUnexpectedResponse = (_request: unknown, response: { statusCode?: number; statusMessage?: string }) => {
        finish(
          new Error(
            `Server ${this.server.id} auth or handshake rejected: ${response.statusCode ?? "unknown"} ${response.statusMessage ?? ""}`.trim(),
          ),
        );
      };

      socket.on("message", (raw) => this.handleMessage(raw.toString()));
      socket.on("close", () => {
        if (this.socket === socket) {
          this.socket = undefined;
        }
        this.rejectPending(new Error(`Server ${this.server.id} connection closed`));
      });
      socket.once("open", handleOpen);
      socket.once("error", handleError);
      socket.once("unexpected-response", handleUnexpectedResponse);
    });

    return this.connecting;
  }

  private handleMessage(raw: string): void {
    let message: unknown;
    try {
      message = JSON.parse(raw);
    } catch (error) {
      this.rejectPending(new Error(`Server ${this.server.id} sent invalid JSON: ${(error as Error).message}`));
      return;
    }

    if (!isToolResponse(message)) return;
    const pending = this.pending.get(message.id);
    if (!pending) return;

    this.pending.delete(message.id);
    clearTimeout(pending.timeout);

    if (message.ok) {
      pending.resolve(asJsonObject(message.result));
      return;
    }

    const error = asJsonObject(message.error);
    const type = typeof error.type === "string" ? error.type : "McAiToolError";
    const text = typeof error.message === "string" ? error.message : "mcAI tool returned an error";
    pending.reject(new FleetToolError(type, text, this.server.id, "unknown"));
  }

  private rejectPending(error: Error): void {
    for (const [id, pending] of this.pending) {
      clearTimeout(pending.timeout);
      pending.reject(error);
      this.pending.delete(id);
    }
  }
}

function isToolResponse(value: unknown): value is { id: string; ok: boolean; result?: unknown; error?: unknown } {
  return typeof value === "object" && value !== null && typeof (value as { id?: unknown }).id === "string";
}

function asJsonObject(value: unknown): JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as JsonObject) : {};
}
