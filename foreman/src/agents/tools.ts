import { z } from 'zod';
import type { ToolDef } from './runner.js';

/** What the team tools can do; implemented by the team backend. */
export interface TeamApi {
  sendMessage(from: string, to: string, text: string): string;
  askUser(from: string, question: string, options: string[], context?: string): Promise<string>;
  readMemory(agentId: string, scope: string, name?: string): string;
  writeMemory(agentId: string, scope: string, title: string, body: string): string;
  listTasks(): string;
  updateTask(agentId: string, status: 'review' | 'blocked', summary: string, module?: string): string;
  reportStatus(agentId: string, activity: string): string;
  createTask(
    from: string,
    t: {
      title: string;
      description?: string;
      kind: string;
      assignee?: string;
      deps: string[];
      module?: string;
      paths: string[];
      priority?: number;
    },
  ): string;
  requestMerge(from: string, taskId: string, notes: string): string;
  requestChanges(from: string, taskId: string, feedback: string): string;
  /** unread messages for the agent, cleared once read */
  drainInbox(agentId: string): string;
}

export function buildTools(agentId: string, lead: boolean, api: TeamApi): ToolDef[] {
  const wrap =
    (fn: (args: Record<string, unknown>) => string | Promise<string>) => async (args: Record<string, unknown>) => {
      try {
        const out = await fn(args);
        const inbox = api.drainInbox(agentId);
        return inbox ? `${out}\n\n${inbox}` : out;
      } catch (e) {
        return `Error: ${e instanceof Error ? e.message : String(e)}`;
      }
    };
  const tools: ToolDef[] = [
    {
      name: 'send_message',
      description:
        'Send a message to a teammate (agent id), to "all" agents, or to "user" (the admins, shown in game chat). Italian for people.',
      shape: { to: z.string().min(1).max(64), text: z.string().min(1).max(2000) },
      handler: wrap((a) => api.sendMessage(agentId, String(a.to), String(a.text))),
    },
    {
      name: 'ask_user',
      description:
        'Ask the admins a question and wait for the answer (they answer in game). Use only for choices only they can make. Options: 2-6 short Italian labels, first = recommended.',
      shape: {
        question: z.string().min(1).max(500),
        options: z.array(z.string().min(1).max(100)).max(6).optional(),
        context: z.string().max(2000).optional(),
      },
      handler: wrap((a) =>
        api.askUser(
          agentId,
          String(a.question),
          (a.options as string[] | undefined) ?? [],
          a.context as string | undefined,
        ),
      ),
    },
    {
      name: 'read_memory',
      description: 'Read team notes. scope "shared" or "mine"; without name, lists the notes.',
      shape: { scope: z.enum(['shared', 'mine']).default('shared'), name: z.string().max(100).optional() },
      handler: wrap((a) => api.readMemory(agentId, String(a.scope ?? 'shared'), a.name as string | undefined)),
    },
    {
      name: 'write_memory',
      description: 'Write a markdown note (Italian) to shared team memory or your own. Same title overwrites.',
      shape: {
        scope: z.enum(['shared', 'mine']),
        title: z.string().min(1).max(100),
        body: z.string().min(1).max(20_000),
      },
      handler: wrap((a) => api.writeMemory(agentId, String(a.scope), String(a.title), String(a.body))),
    },
    {
      name: 'list_tasks',
      description: 'The task board: ids, status, assignee, dependencies.',
      shape: {},
      handler: wrap(() => api.listTasks()),
    },
    {
      name: 'report_status',
      description: 'Set the short status line shown above your head in game (max 48 chars, Italian).',
      shape: { activity: z.string().min(1).max(48) },
      handler: wrap((a) => api.reportStatus(agentId, String(a.activity))),
    },
  ];
  if (!lead) {
    tools.push({
      name: 'update_task',
      description:
        'Finish your current task: status "review" when done (summary of what changed and how you tested it, module = folder you changed) or "blocked" with the reason.',
      shape: {
        status: z.enum(['review', 'blocked']),
        summary: z.string().min(1).max(4000),
        module: z.string().max(300).optional(),
      },
      handler: wrap((a) =>
        api.updateTask(agentId, a.status as 'review' | 'blocked', String(a.summary), a.module as string | undefined),
      ),
    });
  }
  if (lead) {
    tools.push(
      {
        name: 'create_task',
        description:
          'Create a task on the board. kind: code | qa | analysis | generic. assignee: a worker id of the right role. deps: task ids that must be done first. module: the folder it changes. paths: folders to check out for it.',
        shape: {
          title: z.string().min(1).max(120),
          description: z.string().max(4000).optional(),
          kind: z.enum(['code', 'qa', 'analysis', 'generic']),
          assignee: z.string().max(64).optional(),
          deps: z.array(z.string().max(64)).default([]),
          module: z.string().max(300).optional(),
          paths: z.array(z.string().max(300)).max(20).default([]),
          priority: z.number().int().min(-10).max(10).optional(),
        },
        handler: wrap((a) =>
          api.createTask(agentId, {
            title: String(a.title),
            ...(a.description ? { description: String(a.description) } : {}),
            kind: String(a.kind),
            ...(a.assignee ? { assignee: String(a.assignee) } : {}),
            deps: (a.deps as string[] | undefined) ?? [],
            ...(a.module ? { module: String(a.module) } : {}),
            paths: (a.paths as string[] | undefined) ?? [],
            ...(typeof a.priority === 'number' ? { priority: a.priority } : {}),
          }),
        ),
      },
      {
        name: 'request_merge',
        description:
          'After reviewing a task in "review": ask the admins to approve the merge (they decide in game). Notes in Italian.',
        shape: { taskId: z.string().max(64), notes: z.string().max(2000) },
        handler: wrap((a) => api.requestMerge(agentId, String(a.taskId), String(a.notes))),
      },
      {
        name: 'request_changes',
        description: 'After reviewing a task in "review": send it back to its worker with feedback (Italian).',
        shape: { taskId: z.string().max(64), feedback: z.string().min(1).max(4000) },
        handler: wrap((a) => api.requestChanges(agentId, String(a.taskId), String(a.feedback))),
      },
    );
  }
  return tools;
}
