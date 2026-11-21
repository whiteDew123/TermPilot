import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { apply } from '../host.js'

const here = dirname(fileURLToPath(import.meta.url))
const plugin = dirname(here)
const source = readFileSync(join(plugin, 'host.js'), 'utf8')
const tools = []
const ctx = {
  systemPrompt: { section: () => () => {} },
  commands: { register: () => () => {} },
  tools: { register: definition => { tools.push(definition); return () => {} } },
  effect: callback => {
    const disposer = callback()
    return typeof disposer === 'function' ? disposer : () => {}
  },
}

apply(ctx, {})
assert.equal(tools.length, 2)
assert.deepEqual(tools.map(tool => tool.name).sort(), [
  'paiwarp_creator_run_chapter_two',
  'paiwarp_creator_terminal_input',
])
for (const tool of tools) {
  assert.equal(tool.output.schema.type, 'string', `${tool.name} must declare a string result`)
  const blocks = tool.output.render({}, '{"status":"waiting_input"}')
  assert.deepEqual(blocks, [{ type: 'text', text: '{"status":"waiting_input"}' }])
}
assert.equal(tools[0].output, tools[1].output, 'both tools share the string output contract')
assert.match(source, /entry\.next\.resolve\(JSON\.stringify\(/)
assert.match(source, /const reply = JSON\.stringify\(/)
assert.match(source, /entry\.next\.resolve\(reply\)/)
assert.doesNotMatch(source, /schema:\s*\{\s*type:\s*'object'/)
assert.match(source, /'DEEPSEEK_API_KEY', 'DEEPSEEK_BASE_URL', 'DEEPSEEK_MODEL'/)
assert.doesNotMatch(source, /SEARCH_PROVIDER|SEARXNG_URL|TERMPILOT_AUTO_CLASSIFIER_MODEL/)
assert.ok(!source.includes('API_KEY|BASE_URL|MODEL'))
console.log('host string output + DeepSeek env self-check: ok')
