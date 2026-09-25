import { loadConfig } from "./config.js";
import { ChatGateway } from "./server.js";

async function main(): Promise<void> {
  const config = loadConfig();
  const gateway = new ChatGateway(config);
  const address = await gateway.start();
  console.info(`Chat server listening on port ${address.port}`);

  let stopping = false;
  const shutdown = (signal: string): void => {
    if (stopping) return;
    stopping = true;
    console.info(`Chat server stopping (${signal})`);
    void gateway.stop().catch((error: unknown) => {
      const name = error instanceof Error ? error.name : "UnknownError";
      console.error(`Chat server shutdown failed (${name})`);
      process.exitCode = 1;
    });
  };
  process.once("SIGTERM", () => shutdown("SIGTERM"));
  process.once("SIGINT", () => shutdown("SIGINT"));
}

main().catch((error: unknown) => {
  const name = error instanceof Error ? error.name : "UnknownError";
  console.error(`Chat server failed to start (${name})`);
  process.exitCode = 1;
});
