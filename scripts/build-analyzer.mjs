import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const accept = process.argv.slice(2).includes("--accept-modules");
const windows = process.platform === "win32";

const [program, args] = windows
  ? [
      "pwsh",
      [
        "-NoProfile",
        "-File",
        join(here, "build-analyzer.ps1"),
        ...(accept ? ["-AcceptModules"] : []),
      ],
    ]
  : [
      "bash",
      [
        join(here, "build-analyzer.sh"),
        ...(accept ? ["--accept-modules"] : []),
      ],
    ];

const result = spawnSync(program, args, { stdio: "inherit" });
if (result.error) {
  process.stderr.write(
    `build-analyzer.mjs: não foi possível rodar ${program}: ${result.error.message}\n`
  );
  process.exit(2);
}
process.exit(result.status ?? 1);
