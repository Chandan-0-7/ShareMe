#!/usr/bin/env python3
"""ShareMe: dependency-free LAN file sharing."""
import argparse
import hmac
import json
import mimetypes
from pathlib import Path
import secrets
import shutil
import socket
import tempfile
import threading
from http import cookies
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, urlsplit
import zipfile

WEB = Path(__file__).parent / 'web'


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, data, kind='application/json', extra=None):
        if isinstance(data, (dict, list)):
            data = json.dumps(data).encode()
        elif isinstance(data, str):
            data = data.encode()
        self.send_response(status)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)

    def authorized(self):
        jar = cookies.SimpleCookie()
        try:
            jar.load(self.headers.get('Cookie', ''))
            return hmac.compare_digest(jar['session'].value, self.server.token)
        except (KeyError, cookies.CookieError):
            return False

    def safe_path(self, value):
        if '\\' in value or '\x00' in value:
            raise ValueError('Invalid path')
        path = (self.server.root / value).resolve()
        if path != self.server.root and self.server.root not in path.parents:
            raise ValueError('Path is outside shared folder')
        return path

    def do_GET(self):
        route = urlsplit(self.path)
        if route.path in ('/', '/app.js', '/style.css'):
            filename = 'index.html' if route.path == '/' else route.path[1:]
            self.reply(200, (WEB / filename).read_bytes(), mimetypes.guess_type(filename)[0] or 'text/plain')
            return
        if not self.authorized():
            self.reply(401, {'error': 'Pair this device first.'})
            return
        try:
            if route.path == '/api/files':
                entries = []
                for path in sorted(self.server.root.rglob('*')):
                    if path.is_symlink() or self.server.root not in path.resolve().parents:
                        continue
                    entries.append({'path': path.relative_to(self.server.root).as_posix(), 'folder': path.is_dir(), 'size': path.stat().st_size if path.is_file() else 0})
                self.reply(200, entries)
            elif route.path == '/api/download':
                path = self.safe_path(parse_qs(route.query).get('path', [''])[0])
                if not path.exists():
                    self.reply(404, {'error': 'File no longer exists.'})
                    return
                filename = path.name
                if path.is_dir():
                    stream = tempfile.TemporaryFile()
                    with zipfile.ZipFile(stream, 'w', zipfile.ZIP_DEFLATED) as archive:
                        for child in path.rglob('*'):
                            if child.is_file() and not child.is_symlink() and self.server.root in child.resolve().parents:
                                archive.write(child, child.relative_to(path))
                    filename += '.zip'
                    stream.seek(0, 2)
                    length = stream.tell()
                    stream.seek(0)
                else:
                    stream = path.open('rb')
                    length = path.stat().st_size
                with stream:
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/octet-stream')
                    self.send_header('Content-Length', str(length))
                    self.send_header('Content-Disposition', "attachment; filename*=UTF-8''" + quote(filename, safe=''))
                    self.end_headers()
                    shutil.copyfileobj(stream, self.wfile)
            else:
                self.reply(404, {'error': 'Not found'})
        except (ValueError, OSError) as error:
            self.reply(400, {'error': str(error)})

    def do_POST(self):
        # Custom header prevents cross-origin form submissions; no CORS is enabled.
        if self.headers.get('X-ShareMe') != '1':
            self.reply(403, {'error': 'Invalid request'})
            return
        route = urlsplit(self.path)
        if route.path == '/api/pair':
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 < size < 1024:
                    raise ValueError()
                payload = json.loads(self.rfile.read(size))
                code = payload.get('code', '') if isinstance(payload, dict) else ''
                valid = isinstance(code, str) and hmac.compare_digest(code, self.server.code)
            except (ValueError, TypeError):
                valid = False
            if valid:
                self.reply(200, {'ok': True}, extra={'Set-Cookie': 'session=' + self.server.token + '; HttpOnly; SameSite=Strict; Path=/'})
            else:
                self.reply(403, {'error': 'Incorrect pairing code.'})
            return
        if not self.authorized():
            self.reply(401, {'error': 'Pair this device first.'})
            return
        if route.path != '/api/upload':
            self.reply(404, {'error': 'Not found'})
            return
        temporary = None
        try:
            destination = self.safe_path(parse_qs(route.query).get('path', [''])[0])
            size = int(self.headers.get('Content-Length', '-1'))
            if destination == self.server.root or size < 0:
                raise ValueError('File name and content length are required.')
            destination.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(dir=self.server.staging, delete=False) as stream:
                temporary = Path(stream.name)
                remaining = size
                while remaining:
                    chunk = self.rfile.read(min(1024 * 1024, remaining))
                    if not chunk:
                        raise ValueError('Upload interrupted')
                    stream.write(chunk)
                    remaining -= len(chunk)
            with self.server.lock:
                original = destination
                index = 1
                while destination.exists():
                    destination = original.with_name(f'{original.stem} ({index}){original.suffix}')
                    index += 1
                shutil.move(str(temporary), str(destination))
            self.reply(201, {'path': destination.relative_to(self.server.root).as_posix()})
        except (ValueError, OSError) as error:
            self.reply(400, {'error': str(error)})
        finally:
            if temporary and temporary.exists():
                temporary.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8080)
    parser.add_argument('--folder', default='Shared')
    args = parser.parse_args()
    server = ThreadingHTTPServer(('0.0.0.0', args.port), Handler)
    server.root = Path(args.folder).resolve()
    server.root.mkdir(parents=True, exist_ok=True)
    server.token = secrets.token_hex(32)
    server.code = secrets.token_hex(4).upper()
    server.lock = threading.Lock()
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(('192.0.2.1', 80))
            address = probe.getsockname()[0]
    except OSError:
        address = socket.gethostbyname(socket.gethostname())
    print(f'\nShareMe is ready\nComputer: http://localhost:{args.port}\nPhone:    http://{address}:{args.port}\nPair code: {server.code}\nShared folder: {server.root}\nKeep this terminal open. Press Ctrl+C to stop.\n', flush=True)
    with tempfile.TemporaryDirectory(prefix='shareme-') as staging:
        server.staging = staging
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            server.server_close()


if __name__ == '__main__':
    main()
