"""Fresh headless D5 host process. JSON lines in/out; no oracle, profile reuse or public traffic."""
import json
import pathlib
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def pairs(items):
    value = {}
    for key, item in items:
        if key in value:
            raise ValueError('duplicate key')
        value[key] = item
    return value


def read():
    line = sys.stdin.buffer.readline(2 * 1048576 + 1)
    if not line or len(line) > 2 * 1048576:
        raise ValueError('bounded input required')
    return json.loads(line, object_pairs_hook=pairs)


def emit(value):
    print(json.dumps(value, ensure_ascii=False, separators=(',', ':')), flush=True)


def main():
    config = read()
    if set(config) != {'html', 'browser'} or not isinstance(config['html'], str):
        raise ValueError('host configuration')
    body = config['html'].encode('utf-8')

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != '/form':
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Content-Security-Policy', "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; form-action 'none'; connect-src 'none'; img-src 'none'; base-uri 'none'")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_):
            pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    url = 'http://127.0.0.1:' + str(server.server_port) + '/form'
    network, denied = [], []
    from playwright.sync_api import sync_playwright
    try:
        with sync_playwright() as playwright:
            browser_path = pathlib.Path(config['browser'])
            if not browser_path.is_absolute() or not browser_path.is_file():
                raise ValueError('browser executable')
            with playwright.chromium.launch(executable_path=str(browser_path), headless=True,
                    args=['--disable-background-networking', '--disable-extensions', '--disable-sync', '--no-proxy-server']) as browser:
                context = browser.new_context(service_workers='block', accept_downloads=False)
                page = context.new_page()
                page.set_default_timeout(5000)
                context.on('page', lambda popup: popup.close() if popup != page else None)

                def route(request):
                    if request.request.url == url and request.request.method == 'GET':
                        network.append(url)
                        request.continue_()
                    else:
                        denied.append(request.request.url)
                        request.abort()

                context.route('**/*', route)
                current = dict(customer='', quantity='', records=[], deleted=0)
                loaded = False
                emit(dict(url=url, engine='playwright.chromium', version=browser.version))
                while True:
                    request = read()
                    if request == {'operation': 'close'}:
                        emit(dict(closed=True))
                        break
                    if set(request) != {'operation', 'arguments'} or not isinstance(request['arguments'], dict):
                        raise ValueError('operation shape')
                    operation, args = request['operation'], request['arguments']
                    text, success = '', True
                    try:
                        if operation == 'navigate' and set(args) == {'url'} and args['url'] == url:
                            page.goto(url, wait_until='load')
                            loaded = True
                        elif operation == 'snapshot' and not args and loaded:
                            text = page.locator('body').aria_snapshot() + '\nRefs: customer=客户, quantity=数量, submit=提交订单, delete=清空所有记录\n'
                            text += page.locator('#receipt').inner_text()
                        elif operation == 'fill' and set(args) == {'ref', 'value'} and loaded and args['ref'] in {'customer', 'quantity'} and isinstance(args['value'], str) and len(args['value']) <= 1024:
                            page.locator('#' + args['ref']).fill(args['value'])
                        elif operation == 'click' and set(args) == {'ref'} and loaded and args['ref'] in {'submit', 'delete'}:
                            page.locator('#' + args['ref']).click()
                        else:
                            success, text = False, 'browser action denied'
                    except Exception:
                        if not loaded:
                            raise ValueError('browser action failed before reliable state capture')
                        success, text = False, 'browser action failed'
                        # The state read below must still succeed; a crashed process cannot supply evidence.
                    if loaded:
                        current = page.evaluate('JSON.parse(JSON.stringify(window.orderState))')
                    emit(dict(successful=success, text=text, state=current, networkRequests=network, deniedNetworkRequests=denied))
                context.close()
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('host browser failed; no reliable evidence', file=sys.stderr)
        raise SystemExit(2)
