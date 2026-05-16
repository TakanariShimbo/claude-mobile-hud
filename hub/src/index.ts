// Hub daemon entry. Phase 4 で実装。
// Phase 3 §5 (Hub 詳細設計) を参照。

import { argv } from "node:process";

function main(): void {
    const args = argv.slice(2);
    console.error("[hub] claude-mobile-hud Hub - not yet implemented (Phase 4)");
    console.error(`[hub] args: ${JSON.stringify(args)}`);
    process.exit(0);
}

main();
