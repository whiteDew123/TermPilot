import { spawn } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { mkdir, readFile, realpath } from 'node:fs/promises'
import { createInterface } from 'node:readline'
import { createRequire } from 'node:module'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const name = 'paiwarp-dsh-creator'
export const inject = ['tools', 'commands', 'workingDirectory', 'sandboxPolicy', 'systemPrompt']

const here = dirname(fileURLToPath(import.meta.url))
const pluginRoot = resolve(here, '../..')
const xtermRoot = dirname(dirname(createRequire(import.meta.url).resolve('@xterm/xterm')))
const runToolName = 'paiwarp_creator_run_chapter_two'
const inputToolName = 'paiwarp_creator_terminal_input'

const assistantPrompt = [
  '当用户说“用 PaiWarp 创造版复跑第二章”或其他同义请求时，先调用 paiwarp_creator_run_chapter_two。',
  '它会在 termpilot 工作区打开真实本机 PTY，编译并启动 TermPilot，依次跑第二章第 09 节的串行计划、并行 DAG、按 I 修改计划、Shift+Tab 后的 plan 模式普通任务和 /exit，并核对本轮输出、文件、退出码与执行记录。',
  '打开后，每当工具返回 status=waiting_input，就调用 paiwarp_creator_terminal_input。必须原样传回返回的 run、turn 和 expected；expected 是 ENTER、I、SHIFT_TAB 时表示按键，其他值表示要键入的一整行。不要自己改写、不要并发提交。',
  '一直执行到 status=passed 或 status=failed。只在最终状态向用户汇报：是否通过、四张图、MP4、报告和第二章演示副本的位置。计划没有按要求拆分时，报告实际依赖和执行情况，不能声称成功。',
].join('')

function latch() {
  let resolvePromise
  const promise = new Promise(resolve => { resolvePromise = resolve })
  return { promise, resolve: resolvePromise }
}

const DEEPSEEK_ENV = new Set([
  'DEEPSEEK_API_KEY', 'DEEPSEEK_BASE_URL', 'DEEPSEEK_MODEL',
])

function envForCase() {
  const keep = new Set([
    'PATH', 'HOME', 'JAVA_HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'USER', 'LOGNAME', 'SHELL',
    'TERM', 'TERM_PROGRAM', 'COLORTERM',
    ...DEEPSEEK_ENV,
  ])
  const env = {}
  for (const [key, value] of Object.entries(process.env)) {
    if (value === undefined) continue
    if (keep.has(key)) env[key] = value
  }
  env.PATH = [
    process.env.HOME ? `${process.env.HOME}/.jenv/shims` : '',
    '/opt/homebrew/bin', '/usr/local/bin', process.env.PATH || '',
  ].filter(Boolean).join(':')
  env.PYTHONUNBUFFERED = '1'
  env.PAIWARP_CREATOR_RUN = '1'
  return env
}

async function loadProjectEnv(root, env) {
  try {
    const text = await readFile(join(root, '.env'), 'utf8')
    for (const raw of text.split(/\r?\n/)) {
      const line = raw.trim()
      if (!line || line.startsWith('#')) continue
      const at = line.indexOf('=')
      if (at < 1) continue
      const key = line.slice(0, at).trim()
      if (!DEEPSEEK_ENV.has(key)) continue
      if (env[key] !== undefined) continue
      env[key] = line.slice(at + 1).trim().replace(/^(['"])(.*)\1$/, '$2')
    }
  } catch (error) {
    if (error?.code !== 'ENOENT') throw error
  }
  return env
}

const textOutput = {
  schema: { type: 'string' },
  render: (_args, value) => [{ type: 'text', text: value }],
}

export function apply(ctx, config = {}) {
  const runs = new Map()
  const lastLive = new Map()
  const runRoot = typeof config.runRoot === 'string' && config.runRoot.trim()
    ? config.runRoot.trim()
    : 'cases/paiwarp-creator/runs'

  const liveDir = (root, agent) => join(root, '.termpilot', 'paiwarp-creator',
    createHash('sha256').update(String(agent.id)).digest('hex').slice(0, 32))

  async function checkWorkspace(agent, signal) {
    const root = await realpath(await ctx.workingDirectory.ensure(agent, signal))
    const trusted = await realpath(pluginRoot)
    if (root !== trusted) {
      throw new Error('请在这个插件所属的 termpilot 工作区发起第二章复跑。')
    }
    return root
  }

  function makeEntry(child, output, run, mode, agent) {
    const entry = {
      child, output, run, mode, agent,
      ready: null,
      next: latch(),
      final: null,
      diagnostics: '',
      closed: false,
    }
    entry.done = new Promise(resolveDone => { entry.resolveDone = resolveDone })
    return entry
  }

  async function finishEntry(entry, code) {
    if (entry.closed) return
    entry.closed = true
    if (entry.timer) clearTimeout(entry.timer)
    let result
    try {
      result = JSON.parse(await readFile(join(entry.output, 'result.json'), 'utf8'))
    } catch {
      result = {
        status: 'failed',
        chapter: 2,
        error: entry.diagnostics
          ? `本机运行器没有生成 report：${entry.diagnostics}`
          : '本机运行器没有生成 report',
      }
    }
    if (code !== 0 && result.status === 'passed') result.status = 'failed'
    if (code !== 0 && !result.error) result.error = `本机运行器退出码 ${code}`
    const reply = JSON.stringify({
      ...result,
      directory: entry.output,
      relativeDirectory: relative(pluginRoot, entry.output),
    })
    entry.final = reply
    entry.next.resolve(reply)
    if (runs.get(entry.agent.id) === entry) runs.delete(entry.agent.id)
    entry.resolveDone()
  }

  function nextScreen(entry, signal) {
    const stop = () => {
      if (!entry.closed) entry.child.kill('SIGTERM')
    }
    signal.addEventListener('abort', stop, { once: true })
    if (signal.aborted) stop()
    return entry.next.promise.finally(() => signal.removeEventListener('abort', stop))
  }

  async function requestPermission(exec, root) {
    const session = exec.agent?.session
    if (!session) throw new Error('当前工具调用没有关联会话。')
    const mode = ctx.sandboxPolicy.resolve({ session }).mode
    if (mode === 'danger-full-access') return mode
    const approval = ctx.get('approval')
    if (!approval) throw new Error('当前 Harness 没有提供单次审批服务；请使用完全权限会话。')
    let outcome
    try {
      outcome = await approval.request({
        agent: exec.agent,
        callId: exec.callId,
        toolName: runToolName,
        reason: 'PaiWarp 创造版要在本机真实终端编译并运行 TermPilot，复跑第二章计划模式教程，保存截图、录屏和教程副本。',
        displayReason: {
          en: 'PaiWarp Creator will compile and run TermPilot in a real local terminal to replay chapter 2, then save screenshots, a recording, and a local article copy.',
          zh: 'PaiWarp 创造版要在本机真实终端编译并运行 TermPilot，复跑第二章计划模式教程，保存截图、录屏和教程副本。',
        },
        signal: exec.signal,
      })
    } catch (error) {
      throw new Error('本次真实本机运行没有完成授权：' + (error?.message || String(error)))
    }
    if (outcome !== 'allowed-once') throw new Error('本次真实本机运行未获授权。')
    return mode
  }

  ctx.systemPrompt.section({
    name: 'paiwarp-creator:chapter-two',
    order: 531,
    text: assistantPrompt,
  })

  ctx.commands.register({
    name: 'paiwarp-creator',
    description: '查看 PaiWarp 创造版第二章终端，或在人工接管时暂停、恢复、停止和发送按键。',
    input: { hint: 'view | pause | resume | stop | key <JSON字符串>' },
    recordInput: false,
    async handler({ agent, rawInput, signal }) {
      const root = await checkWorkspace(agent, signal)
      const action = rawInput.trimStart()
      if (action === 'view' || !action) {
        return { kind: 'success', text: relative(root, lastLive.get(agent.id) || liveDir(root, agent)) }
      }
      const entry = runs.get(agent.id)
      if (!entry) return { kind: 'error', text: '当前没有正在运行的第二章程。' }
      let command
      if (['pause', 'resume', 'stop'].includes(action)) {
        command = { action }
      } else if (action.startsWith('key ')) {
        let data
        try { data = JSON.parse(action.slice(4)) } catch { return { kind: 'error', text: '按键必须是 JSON 字符串。' } }
        if (typeof data !== 'string' || data.length > 4096) {
          return { kind: 'error', text: '按键内容无效。' }
        }
        command = { action: 'key', data }
      } else {
        return { kind: 'error', text: '未知命令，可用 view、pause、resume、stop、key。' }
      }
      entry.child.stdin.write(JSON.stringify(command) + '\n')
      return { kind: 'success', text: '已发送。' }
    },
  })

  ctx.tools.register({
    name: runToolName,
    description: '复跑 TermPilot 第二章第 09 节计划模式教程。在本机真实 PTY 中编译并启动 TermPilot，串行计划、并行 DAG、按 I 修改计划、Shift+Tab 普通任务和 /exit 都由外层 Agent 分步键入；工具核对本轮输出、文件、退出码和会话记录，保存四张 PNG、MP4、报告及只替换四张图的第二章演示副本。',
    parameters: { type: 'object', properties: {}, additionalProperties: false },
    output: textOutput,
    timeoutMs: 1_800_000,
    async execute(args, exec) {
      if (!exec.agent) throw new Error('需要当前会话。')
      if (args && Object.keys(args).length) throw new Error('这个工具不需要参数。')
      const root = await checkWorkspace(exec.agent, exec.signal)
      if (runs.has(exec.agent.id)) throw new Error('当前会话已经在复跑第二章。')
      const mode = await requestPermission(exec, root)
      exec.signal.throwIfAborted()

      const stamp = new Date().toISOString().replace(/[:.]/g, '-')
      const run = `${stamp}-${randomUUID().slice(0, 8)}`
      const output = join(root, runRoot, run)
      const live = liveDir(root, exec.agent)
      await mkdir(output, { recursive: true })
      await mkdir(live, { recursive: true, mode: 0o700 })
      lastLive.set(exec.agent.id, live)

      const env = await loadProjectEnv(root, envForCase())
      const python = process.env.PAIWARP_CREATOR_PYTHON || '/usr/bin/python3'
      const child = spawn(python, ['-u', join(here, 'creator_runner.py'),
        root, output, live, xtermRoot], {
        cwd: root,
        stdio: ['pipe', 'pipe', 'pipe'],
        env,
      })
      child.stdin.on('error', () => {})
      const entry = makeEntry(child, output, run, mode, exec.agent)
      runs.set(exec.agent.id, entry)

      child.stderr.on('data', chunk => {
        entry.diagnostics = (entry.diagnostics + chunk.toString()).slice(-4000)
      })
      const lines = createInterface({ input: child.stdout })
      lines.on('line', line => {
        let message
        try { message = JSON.parse(line) } catch { return }
        if (message.kind !== 'input') return
        entry.ready = message
        entry.next.resolve(JSON.stringify({
          status: 'waiting_input',
          run: entry.run,
          turn: message.turn,
          expected: message.expected,
          phase: message.phase,
          screen: message.screen,
          directory: output,
          relativeDirectory: relative(root, output),
        }))
      })
      child.once('error', error => {
        entry.diagnostics = (entry.diagnostics + `spawn: ${error.message}`).slice(-4000)
      })
      child.once('close', code => { finishEntry(entry, code) })

      if (exec.agent.ctx?.effect) {
        exec.agent.ctx.effect(() => () => {
          if (runs.get(exec.agent.id) === entry && !entry.closed) child.kill('SIGTERM')
          return entry.done
        }, 'paiwarp-creator owner')
      }
      return nextScreen(entry, exec.signal)
    },
  })

  ctx.tools.register({
    name: inputToolName,
    description: '向当前会话的 PaiWarp 创造版第二章 PTY 发送上一步要求的按键或一整行。必须使用 waiting_input 返回的 run、turn 和 expected 原文；ENTER、I、SHIFT_TAB 是按键标记，其他值是普通文本。',
    parameters: {
      type: 'object',
      properties: {
        run: { type: 'string', description: 'waiting_input 返回的运行编号。' },
        turn: { type: 'integer', description: 'waiting_input 返回的步骤号。' },
        text: { type: 'string', description: 'waiting_input 返回的 expected 原文。' },
      },
      required: ['run', 'turn', 'text'],
      additionalProperties: false,
    },
    output: textOutput,
    timeoutMs: 900_000,
    async execute(args, exec) {
      if (!exec.agent) throw new Error('需要当前会话。')
      const entry = runs.get(exec.agent.id)
      if (!entry) throw new Error('当前会话没有正在运行的第二章程。')
      if (entry.final) return entry.final
      if (entry.run !== args.run) throw new Error('这个运行编号不属于当前会话。')
      const session = exec.agent.session
      if (!session) throw new Error('当前工具调用没有关联会话。')
      if (ctx.sandboxPolicy.resolve({ session }).mode !== entry.mode) {
        entry.child.kill('SIGTERM')
        await entry.done
        throw new Error('会话权限在运行中改变，已停止本次复跑。')
      }
      const step = entry.ready
      if (!step || step.turn !== args.turn || step.expected !== args.text) {
        throw new Error('输入与当前步骤不匹配，本次没有发送。' +
          (step
            ? JSON.stringify({ run: entry.run, turn: step.turn, expected: step.expected })
            : '当前步骤仍在执行，或已经结束。'))
      }
      exec.signal.throwIfAborted()
      entry.ready = null
      entry.next = latch()
      entry.child.stdin.write(JSON.stringify({
        action: 'send', turn: step.turn, text: args.text,
      }) + '\n')
      return nextScreen(entry, exec.signal)
    },
  })

  ctx.effect(() => async () => {
    const entries = [...runs.values()]
    for (const entry of entries) {
      if (!entry.closed) entry.child.kill('SIGTERM')
    }
    await Promise.allSettled(entries.map(entry => entry.done))
  }, 'paiwarp-creator processes')
}
