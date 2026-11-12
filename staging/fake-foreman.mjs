// A scripted stand-in for the foreman, used by e2e-deploy.sh: it answers the plugin's hello with a
// snapshot holding an approved deploy decision, then asks for the server tools one by one and
// writes every tool.result to RESULTS (JSON lines). Usage: node fake-foreman.mjs <port> <jar>
import { appendFileSync, readFileSync } from 'node:fs';
import { setTimeout } from 'node:timers';
import { URL } from 'node:url';
import { WebSocketServer } from 'ws';

const [port, jar] = process.argv.slice(2);
const token = process.env.AGENTCRAFT_TOKEN;
const out = process.env.RESULTS;
const snapshot = JSON.parse(readFileSync(new URL('../protocol/examples/snapshot.json', import.meta.url), 'utf8'));
const agent = (id, role) => ({ ...snapshot.agents[0], id, name: id, role, taskId: undefined, state: 'idle' });
snapshot.agents = [agent('lucciola', 'developer'), agent('briciola', 'qa')];
snapshot.tasks = [];
snapshot.goals = [];
const now = Date.now();
const decision = (id, status, option) => ({
  id,
  agentId: 'lucciola',
  kind: 'deploy',
  question: 'Installare HelloAgent 2.0 sul server di prova?',
  options: ['Installa e riavvia', 'Installa, riavvio io', 'No'],
  status,
  createdAt: now,
  ...(option ? { answer: { option, by: 'Tester', ts: now } } : {}),
});
snapshot.decisions = [decision('d1', 'answered', 'Installa e riavvia'), decision('d2', 'open')];

const steps = [
  ['briciola', 'server.metrics', {}],
  ['briciola', 'server.logs', { limit: 50, filter: 'helloagent' }],
  ['briciola', 'server.command', { command: 'list', as: 'console' }],
  ['briciola', 'server.command', { command: 'op Tester', as: 'console' }],
  ['briciola', 'player.simulate', { actions: [{ kind: 'command', command: 'help' }] }],
  ['briciola', 'server.deploy', { file: jar, approvalId: 'd1' }],
  ['lucciola', 'server.deploy', { file: jar, approvalId: 'd2' }],
  ['lucciola', 'server.deploy', { file: '/etc/passwd', approvalId: 'd1' }],
  ['lucciola', 'server.deploy', { file: jar, approvalId: 'd1' }],
  ['lucciola', 'server.restart', { approvalId: 'd1', delaySeconds: 5 }],
];

const wss = new WebSocketServer({ host: '127.0.0.1', port: Number(port) });
let connections = 0;
wss.on('connection', (ws) => {
  const first = ++connections === 1; // after the restart the plugin only says hello
  let step = 0;
  const next = () => {
    if (step >= steps.length) return;
    const [agentId, tool, args] = steps[step];
    ws.send(
      JSON.stringify({
        v: 1,
        type: 'tool.call',
        id: `c${step}`,
        agentId,
        requestedBy: 'Tester',
        deadlineMs: 60000,
        call: { tool, args },
      }),
    );
  };
  ws.on('message', (data) => {
    const msg = JSON.parse(data.toString());
    if (msg.type === 'hello') {
      if (msg.token !== token) return ws.close(4401, 'bad token');
      ws.send(JSON.stringify(snapshot));
      appendFileSync(out, JSON.stringify({ hello: msg.pluginVersion }) + '\n');
      if (first) setTimeout(next, 1000);
      return;
    }
    if (msg.type === 'tool.result') {
      appendFileSync(
        out,
        JSON.stringify({
          tool: steps[step][1],
          agent: steps[step][0],
          ok: msg.ok,
          result: msg.result,
          error: msg.error,
        }) + '\n',
      );
      step++;
      next();
    }
  });
});
