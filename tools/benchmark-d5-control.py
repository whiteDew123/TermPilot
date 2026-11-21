#!/usr/bin/env python3
"""Run D5 on a fresh headless browser against one loopback page, without model APIs.

This is a development browser control, not the formal TermPilot browser relay.
Requires an installed Python Playwright package and Chromium/Google Chrome.
"""
import argparse
import copy
import hashlib
import json
import pathlib
import shutil
import stat
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def pairs(items):
    result = {}
    for key, value in items:
        require(key not in result, 'duplicate JSON key')
        result[key] = value
    return result


def load(path):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 16 * 1048576, 'input file')
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=pairs)


def write(path, value):
    with path.open('x', encoding='utf-8') as output:
        json.dump(value, output, ensure_ascii=False, separators=(',', ':'))
        output.write('\n')
    path.chmod(0o600)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=pathlib.Path, required=True)
    parser.add_argument('--episode', type=pathlib.Path, required=True)
    parser.add_argument('--scenario', choices=['correct', 'duplicate', 'destructive'], default='correct')
    parser.add_argument('--browser', type=pathlib.Path)
    args = parser.parse_args()
    source, episode = args.source, args.episode
    require(source.is_absolute() and source == source.resolve() and not source.is_symlink(), 'canonical source')
    require(episode.is_absolute() and not episode.exists() and episode.parent == episode.parent.resolve()
            and stat.S_IMODE(episode.parent.stat().st_mode) == 0o700, 'new private episode root')
    require(not any((p / '.git').exists() for p in [episode.parent, *episode.parent.parents]), 'episode must remain outside Git')
    oracle_path = source / 'validators/final/_private/oracles/D5.json'
    oracle = load(oracle_path)
    require(oracle['caseId'] == 'D5' and oracle['profile'] == 'd5-local-form-v1', 'frozen local browser source')
    html = source / 'fixtures/final/D5/form.html'
    require(not html.is_symlink() and html.read_text(encoding='utf-8') == oracle['files']['form.html'], 'fixture drift')
    template = load(source / 'references/final/D5/evidence.json')
    require(template['browser']['sourceSha256'] == hashlib.sha256(oracle_path.read_bytes()).hexdigest(), 'source identity')
    episode.mkdir(mode=0o700)
    workspace = episode / 'workspace'; workspace.mkdir(mode=0o700)
    shutil.copyfile(html, workspace / 'form.html'); (workspace / 'form.html').chmod(0o600)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != '/form':
                self.send_error(404); return
            body = html.read_bytes()
            self.send_response(200); self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)

        def log_message(self, *_):
            pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    url = 'http://127.0.0.1:' + str(server.server_port) + '/form'
    events, network, denied, snapshots = [], [], [], []
    from playwright.sync_api import sync_playwright
    try:
        with sync_playwright() as playwright:
            browser_path = args.browser
            if browser_path is None:
                installed = pathlib.Path('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')
                if installed.is_file():
                    browser_path = installed
            launch = dict(headless=True, args=['--disable-background-networking'])
            if browser_path is not None:
                require(browser_path.is_file(), 'browser executable missing')
                launch['executable_path'] = str(browser_path)
            with playwright.chromium.launch(**launch) as browser:
                context = browser.new_context(service_workers='block')
                page = context.new_page()
                page.set_default_timeout(10000)

                def route(request_route):
                    requested = request_route.request.url
                    if requested == url:
                        network.append(requested); request_route.continue_()
                    else:
                        denied.append(requested); request_route.abort()

                context.route('**/*', route)

                def state():
                    return page.evaluate('JSON.parse(JSON.stringify(window.orderState))')

                def event(name, arguments, action):
                    action()
                    events.append(dict(sequence=len(events) + 1, name=name, arguments=arguments,
                                       successful=True, state=state()))

                event('navigate', dict(url=url), lambda: page.goto(url, wait_until='load'))

                def snapshot():
                    snapshots.append(page.locator('body').aria_snapshot())

                event('snapshot', {}, snapshot)
                # These stable refs belong to this generated page and appear in its observed form.
                require('客户' in snapshots[-1] and '数量' in snapshots[-1] and '提交订单' in snapshots[-1], 'form refs unavailable')
                if args.scenario == 'destructive':
                    event('click', dict(ref='delete'), lambda: page.get_by_role('button', name='清空所有记录').click())
                expected = oracle['expected']
                event('fill', dict(ref='customer', value=expected['customer']), lambda: page.get_by_role('textbox', name='客户').fill(expected['customer']))
                event('fill', dict(ref='quantity', value=str(expected['quantity'])), lambda: page.get_by_role('spinbutton', name='数量').fill(str(expected['quantity'])))
                event('click', dict(ref='submit'), lambda: page.get_by_role('button', name='提交订单').click())
                if args.scenario == 'duplicate':
                    event('click', dict(ref='submit'), lambda: page.get_by_role('button', name='提交订单').click())
                event('snapshot', {}, snapshot)
                final_state = state()
                answer = page.locator('#receipt').inner_text()
                context.close()
        evidence = copy.deepcopy(template)
        evidence['answer'] = answer
        evidence['browser'] = dict(sourceSha256=hashlib.sha256(oracle_path.read_bytes()).hexdigest(),
                                   referenceOrigin='HEADLESS_BROWSER_CONTROL_NOT_MODEL_EVIDENCE', events=events,
                                   finalState=final_state, networkRequests=network, deniedNetworkRequests=denied)
        metrics = evidence['llmMetrics']
        for key in ['calls', 'successfulCalls', 'inputTokens', 'outputTokens', 'cachedInputTokens']:
            metrics[key] = 0
        metrics['toolCalls'] = len(events); metrics['resolvedModel'] = 'no-model-browser-control'
        metrics['resolvedModelConsistent'] = False; metrics['usageComplete'] = False; metrics['requestFingerprintComplete'] = False
        write(episode / 'evidence.json', evidence)
        write(episode / 'snapshots.json', snapshots)
        write(episode / 'control-status.json', dict(caseId='D5', realProviderCalls=0, publishable=False,
              scenario=args.scenario, headlessBrowser=True, formalBrowserRelay=False, deniedNetworkRequests=denied))
        print('saved=' + str(episode / 'evidence.json'))
    finally:
        server.shutdown(); server.server_close(); thread.join(timeout=5)


if __name__ == '__main__':
    main()
