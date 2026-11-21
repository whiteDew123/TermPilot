window.__ModuleLoader__.load({
  id: 'paiwarp-dsh-creator',
  factory(require) {
    const React = require('react')
    const h = React.createElement

    const names = {
      'p2-01-serial-plan': '串行计划',
      'p2-02-parallel-plan': '并行 DAG',
      'p2-03-revised-plan': '按 I 修改',
      'p2-04-plan-mode-task': 'plan 模式',
    }
    const token = {
      panel: 'var(--dsw-alias-bg-layer-1)',
      base: 'var(--dsw-alias-bg-base)',
      raised: 'var(--dsw-alias-bg-layer-2)',
      border: 'var(--dsw-alias-border-l1)',
      text: 'var(--dsw-alias-label-primary)',
      muted: 'var(--dsw-alias-label-secondary)',
      brand: 'var(--dsw-alias-brand-primary)',
      error: 'var(--dsw-alias-state-error-primary)',
      success: 'var(--dsw-alias-state-success-primary)',
    }

    function Button({ children, onClick, active, title, disabled }) {
      return h('button', {
        type: 'button', onClick, title: title || children, disabled,
        'aria-pressed': active ? 'true' : undefined,
        style: {
          font: 'inherit', fontSize: 13, lineHeight: '20px', borderRadius: 7,
          border: '1px solid ' + token.border, padding: '4px 9px',
          color: active ? token.text : token.muted,
          background: active ? token.raised : 'transparent',
          cursor: disabled ? 'default' : 'pointer', opacity: disabled ? 0.55 : 1,
        },
      }, children)
    }

    function useBlobUrl(read, path, mime) {
      const [url, setUrl] = React.useState('')
      const [error, setError] = React.useState('')
      React.useEffect(() => {
        const controller = new AbortController()
        let local
        setUrl(''); setError('')
        if (!path) return () => controller.abort()
        read(path, controller.signal).then(bytes => {
          if (controller.signal.aborted) return
          local = URL.createObjectURL(new Blob(bytes, { type: mime }))
          setUrl(local)
        }).catch(e => {
          if (!controller.signal.aborted) setError(e.message || String(e))
        })
        return () => { controller.abort(); if (local) URL.revokeObjectURL(local) }
      }, [path, mime, read])
      return { url, error }
    }

    function Media({ read, path, video, label }) {
      const { url, error } = useBlobUrl(read, path, video ? 'video/mp4' : 'image/png')
      if (error) return h('small', { style: { color: token.error } }, error)
      if (!url) return h('small', { style: { color: token.muted } }, '读取素材…')
      if (video) return h('video', {
        src: url, controls: true, playsInline: true,
        style: { width: '100%', borderRadius: 8, background: '#000' },
      })
      return h('img', {
        src: url, alt: label,
        style: { width: '100%', borderRadius: 8, border: '1px solid ' + token.border },
      })
    }

    function ToolCard({ phase, open }) {
      React.useEffect(() => {
        if (phase === 'start') open()
      }, [phase, open])
      return h('button', {
        type: 'button', onClick: open,
        style: {
          font: 'inherit', fontSize: 13, border: '1px solid ' + token.border,
          background: token.raised, color: token.text, borderRadius: 8,
          padding: '7px 11px', cursor: 'pointer',
        },
      }, 'PaiWarp 创造版 · 查看第二章实况')
    }

    function Pane({ location, readText, readMedia, send }) {
      const [frame, setFrame] = React.useState('')
      const [state, setState] = React.useState({ phase: '等待本次第二章启动', images: {} })
      const [error, setError] = React.useState('')
      const [input, setInput] = React.useState('')
      const [tab, setTab] = React.useState('live')
      const [article, setArticle] = React.useState('')

      React.useEffect(() => {
        const controller = new AbortController()
        let timer
        let objectUrl = ''
        async function refresh() {
          let delay = 900
          try {
            const base = await location
            let stateData = null
            try {
              stateData = JSON.parse(await readText(base + '/state.json', controller.signal))
            } catch (e) {
              if (controller.signal.aborted) return
            }
            if (stateData) setState(stateData)
            if (!controller.signal.aborted) {
              try {
                const bytes = await readMedia(base + '/frame.jpg', controller.signal)
                const next = URL.createObjectURL(new Blob(bytes, { type: 'image/jpeg' }))
                if (objectUrl) URL.revokeObjectURL(objectUrl)
                objectUrl = next
                setFrame(next)
              } catch (e) {
                if (stateData?.status) setFrame(frame || '')
              }
            }
            setError('')
            if (stateData?.status) delay = 4000
          } catch (e) {
            if (!controller.signal.aborted) setError(e.message || String(e))
          }
          if (!controller.signal.aborted) timer = setTimeout(refresh, delay)
        }
        refresh()
        return () => {
          controller.abort(); clearTimeout(timer)
          if (objectUrl) URL.revokeObjectURL(objectUrl)
        }
      }, [location, readText, readMedia])

      const act = async command => {
        try { await send(command) } catch (e) { setError(e.message || String(e)) }
      }

      const readArticle = async () => {
        try {
          const base = await location
          const path = state.article || (state.dir ? state.dir + '/build-agent-p2-plan-execute.md' : '')
          if (!path) throw new Error('还没有生成教程副本')
          setArticle(await readText(path, new AbortController().signal))
        } catch (e) { setError(e.message || String(e)) }
      }

      const running = !state.status || state.status === 'running'
      const resultTabs = state.status === 'passed' || state.status === 'failed'
      const imageEntries = Object.entries(names).filter(([id]) => state.images && state.images[id])
      const base = state.dir || ''
      const row = { display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }
      const caption = { color: token.muted, fontSize: 12, margin: 0 }

      return h('section', {
        style: { padding: 14, display: 'grid', gap: 12, alignContent: 'start', color: token.text },
      },
        h('div', { style: { display: 'flex', gap: 8, alignItems: 'baseline' } },
          h('strong', { style: { fontSize: 15 } }, 'PaiWarp 创造版'),
          h('span', { style: { color: token.muted, fontSize: 12 } }, '第二章 · 计划模式实跑'),
        ),
        h('p', { 'aria-live': 'polite', style: { margin: 0, color: token.muted, fontSize: 13 } },
          state.phase || '等待本次第二章启动'),
        h('div', { style: row },
          Button({ children: '终端', onClick: () => setTab('live'), active: tab === 'live' }),
          ...imageEntries.map(([id]) => h(Button, {
            key: id, children: names[id], onClick: () => setTab(id), active: tab === id,
          })),
          state.videoSeconds ? Button({ children: '录屏', onClick: () => setTab('video'), active: tab === 'video' }) : null,
          state.article ? Button({ children: '教程副本', onClick: () => { setTab('article'); readArticle() }, active: tab === 'article' }) : null,
        ),
        tab === 'live' && (frame
          ? h('img', {
              src: frame, alt: 'TermPilot 第二章实况',
              style: { width: '100%', borderRadius: 8, border: '1px solid ' + token.border, background: '#14171c' },
            })
          : h('div', {
              style: {
                minHeight: 220, display: 'grid', placeItems: 'center', borderRadius: 8,
                border: '1px dashed ' + token.border, color: token.muted,
              },
            }, running ? '等待终端画面…' : '本次运行没有可读取的实时画面')),
        resultTabs && tab !== 'live' && tab !== 'article' && tab !== 'video' && (state.images || {})[tab]
          ? h(Media, { read: readMedia, path: base + '/' + state.images[tab], label: names[tab] })
          : null,
        resultTabs && tab === 'video'
          ? h(Media, { read: readMedia, path: base + '/session.mp4', video: true, label: '录屏' })
          : null,
        tab === 'article'
          ? h('pre', {
              style: {
                margin: 0, maxHeight: 360, overflow: 'auto', whiteSpace: 'pre-wrap',
                background: token.raised, border: '1px solid ' + token.border,
                borderRadius: 8, padding: 10, fontSize: 12, lineHeight: 1.55,
              },
            }, article || '读取教程副本…')
          : null,
        state.status === 'passed'
          ? h('p', { style: { ...caption, color: token.success } }, '验证通过：四张截图、MP4、报告和本地教程副本已保存。')
          : null,
        state.error
          ? h('p', { style: { ...caption, color: token.error, whiteSpace: 'pre-wrap' } }, state.error)
          : null,
        running && frame
          ? h('div', { style: row },
              Button({
                children: state.paused ? '交回 Agent' : '接管键盘',
                onClick: () => act(state.paused ? 'resume' : 'pause'),
              }),
              Button({ children: '停止', onClick: () => act('stop') }),
            )
          : null,
        state.paused
          ? h('form', {
              onSubmit: event => {
                event.preventDefault()
                act('key ' + JSON.stringify(input + '\r'))
                setInput('')
              },
              style: row,
            },
              h('input', {
                value: input, onChange: event => setInput(event.target.value),
                'aria-label': '输入终端命令',
                style: {
                  flex: '1 1 160px', minWidth: 120, font: 'inherit', padding: '5px 8px',
                  color: token.text, background: token.base, border: '1px solid ' + token.border,
                  borderRadius: 7,
                },
              }),
              h('button', { type: 'submit', style: { font: 'inherit', padding: '5px 9px' } }, '发送'),
              ...Object.entries({ 'Esc': '\x1b', 'Ctrl+C': '\x03', 'Shift+Tab': '\x1b[Z' }).map(([label, value]) =>
                h(Button, { key: label, children: label, onClick: () => act('key ' + JSON.stringify(value)) })),
            )
          : null,
        error ? h('p', { style: { ...caption, color: token.error } }, error) : null,
      )
    }

    return {
      inject: ['slots', 'sidebarRight', 'sidebarRightTabs', 'remote', 'remote.commands', 'remote.workspaceFiles'],
      apply(ctx) {
        ctx.effect(() => ctx.sidebarRightTabs.register({
          id: 'paiwarp-dsh-creator',
          kind: 'paiwarp-creator',
          multiple: false,
          priority: 'builtin',
          title: () => 'PaiWarp 创造版',
          guide: [{
            id: 'open',
            order: 22,
            title: () => 'PaiWarp 创造版',
            description: () => '第二章计划模式实跑与素材预览',
          }],
        }), 'paiwarp-creator.tab')

        ctx.slots.inject('tool.call.toolview', () => ctx.slots.register({
          name: 'tool.call.toolview',
          key: 'paiwarp_creator_run_chapter_two',
          inject: () => ({ open: () => ctx.sidebarRight.openTab('paiwarp-creator') }),
        }, ToolCard))

        ctx.slots.inject('sidebar.right.pane.tab', () => ctx.slots.register({
          name: 'sidebar.right.pane.tab',
          key: 'paiwarp-dsh-creator',
          inject: sessionId => {
            const unwrap = result => {
              if (!result || result.ok === false) throw new Error(result?.error?.message || '读取失败')
              return result.value
            }
            const location = crypto.subtle.digest('SHA-256', new TextEncoder().encode(sessionId)).then(hash =>
              '.termpilot/paiwarp-creator/' +
              Array.from(new Uint8Array(hash).slice(0, 16), n => n.toString(16).padStart(2, '0')).join(''))
            return {
              location,
              send: async command => {
                const value = unwrap(await ctx.remote.commands.execute(sessionId, '/paiwarp-creator ' + command, []))
                if (value?.result?.kind !== 'success') throw new Error(value?.result?.text || '终端不可用')
              },
              readText: async (path, signal) =>
                unwrap(await ctx.remote.workspaceFiles.read(sessionId, path, {}, signal)).text,
              readMedia: async (path, signal) => {
                const chunks = []
                for (let offset = 0; ;) {
                  const page = unwrap(await ctx.remote.workspaceFiles.readBytes(sessionId, path, {
                    range: { offset, length: 512 * 1024 },
                  }, signal))
                  chunks.push(page.data)
                  if (page.eof) return chunks
                  if (!page.data.length) throw new Error('素材读取中断')
                  offset += page.data.length
                }
              },
            }
          },
        }, Pane))
      },
    }
  },
})
