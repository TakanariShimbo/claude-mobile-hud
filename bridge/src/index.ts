// Bridge MCP server entry. Phase 4 で実装。
// Phase 3 §6 (Bridge 詳細設計) を参照。

import { argv } from "node:process";

function main(): void {
    const args = argv.slice(2);
    console.error("[bridge] claude-mobile-hud Bridge - not yet implemented (Phase 4)");
    console.error(`[bridge] args: ${JSON.stringify(args)}`);
    process.exit(0);
}

main();
