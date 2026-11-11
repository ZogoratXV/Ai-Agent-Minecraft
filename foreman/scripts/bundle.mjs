// Bundles the foreman into one ESM file (dist/foreman.mjs) that the plugin ships inside its jar
// and runs with `node`, so admins only install the jar.
import { build } from 'esbuild';

await build({
  entryPoints: ['src/main.ts'],
  outfile: 'dist/foreman.mjs',
  bundle: true,
  platform: 'node',
  format: 'esm',
  target: 'node22',
  minify: false,
  legalComments: 'eof',
  // optional native speed-ups of ws, never required
  // the Agent SDK ships a native binary per platform: installed next to the bundle at run time
  external: ['bufferutil', 'utf-8-validate', '@anthropic-ai/claude-agent-sdk'],
  // CommonJS dependencies (ws) call require() for Node built-ins
  banner: {
    js: "import { createRequire as __acCreateRequire } from 'node:module'; const require = __acCreateRequire(import.meta.url);",
  },
  logLevel: 'warning',
});
console.log('bundled dist/foreman.mjs');
