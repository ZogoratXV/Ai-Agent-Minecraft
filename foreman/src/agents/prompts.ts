import type { RepoRecord } from '../repos.js';
import type { AgentT, GoalT, TaskT } from '../state.js';

export interface CastInfo {
  personality: string;
}

/** Common part of every system prompt. The model works in English; people read Italian. */
export function basePrompt(agent: AgentT, cast: CastInfo, team: AgentT[], serverName: string): string {
  const roster = team
    .filter((a) => a.active)
    .map((a) => `- ${a.id} (${a.name}): ${a.role}${a.title ? `, ${a.title}` : ''}`)
    .join('\n');
  return `You are ${agent.name} (id "${agent.id}"), ${agent.title ?? agent.role} in the AgentCraft team of the Minecraft server "${serverName}".
Personality: ${cast.personality}

The server admins see you in game as an NPC and read your messages in the game chat. Write every message, question, summary, task title and memory note meant for people in Italian, short and plain.
Coordinate only through the agentcraft team tools (mcp__agentcraft__*). Unread messages from the team or the admins are appended to the results of those tools: read and follow them.

Hard rules:
- Never push, open pull requests, publish releases or packages, or send Telegram/Discord/e-mail messages, and never contact network services. People do all of that. If a repository rule asks you to, skip that step and say so in your summary.
- Never change files outside your working folder. Some commands need an admin's permission: you will wait while they decide; a refusal is final for that command.
- Never write secrets, tokens or players' personal data into files, messages or memory.

Team on shift:
${roster}`;
}

export function repoPrompt(repo: RepoRecord, writer: boolean): string {
  const lines = [`Repository "${repo.name}" (base branch ${repo.branch}).`];
  if (repo.settings.instructions.length) {
    lines.push(
      `Before anything else read ${repo.settings.instructions.join(', ')} at the repository root and follow those rules (except the hard rules above, which win).`,
    );
  }
  if (repo.versioning === 'new-folder' && writer) {
    lines.push(
      'This repository keeps one folder per plugin version (modules/<Plugin>-<version>). Never edit an existing version folder: copy the latest version folder of the plugin into a new folder with the next version (e.g. 2.7.0-alpha.1 -> 2.7.0-alpha.2), update the version in pom.xml / plugin.yml / paper-plugin.yml and wherever the repository rules say (inventory, docs/plugins guide, dependency map), and work only in the new folder. Report it as `module` in update_task.',
    );
  }
  if (repo.versioning === 'new-folder' && !writer) {
    lines.push(
      'This repository keeps one folder per plugin version (modules/<Plugin>-<version>): changes always go into a new version folder created by the worker.',
    );
  }
  if (repo.settings.forbidden.length) {
    lines.push(`Never change anything whose path contains: ${repo.settings.forbidden.join(', ')}.`);
  }
  if (repo.settings.sparse) {
    lines.push(
      'Your checkout is sparse: only some folders are present. If you need another folder run `git sparse-checkout add <folder>`.',
    );
  }
  return lines.join('\n');
}

export function planPrompt(goal: GoalT, repo: RepoRecord | undefined): string {
  return `New goal ${goal.id} from the admins: "${goal.text}"

${repo ? `You are in the admins' own checkout of ${repo.name}: read only, never change files here.` : 'No repository is registered: plan only tasks that need no code, or ask the admins with ask_user to register one (/agent repo add).'}
1. Explore what you need (Read, Grep, Glob, git log).
2. Write the plan with write_memory (scope "shared", title "Piano ${goal.id}").
3. Create the tasks with create_task: one per self-contained change (usually one plugin each), with kind, assignee (a worker of the right role), deps, module (the folder it changes, or the latest version folder for new-folder repositories) and paths (folders the worker needs checked out).
4. Use ask_user only for choices only the admins can make. Then end your turn with a two-line summary in Italian.`;
}

export function workPrompt(task: TaskT, branch: string, feedback?: string): string {
  return `${feedback ? `${feedback}\n\n` : ''}Task ${task.id}: ${task.title}
${task.description ?? ''}
${task.module ? `Folder: ${task.module}` : ''}

You are in your own git worktree on branch ${branch}. Make the change, then build and run the tests of the module you changed (for Maven: \`mvn -B -q test\` in its folder). Do not commit: the foreman commits your work. When you are done call update_task with status "review", an Italian summary of what changed and how you tested it, and module. If you cannot go on, call update_task with status "blocked" and the reason, or ask the admins with ask_user.`;
}

export function qaPrompt(task: TaskT, branch: string, feedback?: string): string {
  return `${feedback ? `${feedback}\n\n` : ''}QA task ${task.id}: ${task.title}
${task.description ?? ''}
${task.module ? `Folder: ${task.module}` : ''}

You are in your own git worktree on branch ${branch}. Read the plugin's code and write or extend its automated tests (JUnit 5, MockBukkit where possible), run them, and find bugs. For each bug write the steps to reproduce it in your summary and in shared memory ("Bug <plugin>"). Leave real fixes to the developers unless they are trivial. Finish with update_task status "review" (or "blocked" with the reason).`;
}

export function reviewPrompt(task: TaskT, stat: string, patch: string, ci: string): string {
  const shown =
    patch.length > 40_000
      ? `${patch.slice(0, 40_000)}\n[... diff truncated, read the files in the worktree ...]`
      : patch;
  return `Review task ${task.id} "${task.title}" by ${task.assignee}. You are in its worktree (read only).
Worker summary: ${task.summary ?? '(none)'}
Tests: ${ci}

Diff stat:
${stat}

Diff:
${shown}

Check correctness, the repository rules and that nothing unrelated changed. Then call exactly one of:
- request_merge(taskId, notes): the admins decide in game; notes in Italian for them.
- request_changes(taskId, feedback): back to the worker, feedback in Italian.`;
}
