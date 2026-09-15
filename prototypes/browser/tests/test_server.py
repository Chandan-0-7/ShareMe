import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
import zipfile
import io
from http.server import ThreadingHTTPServer
from server import Handler


class Transfers(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.root = (Path(self.directory.name) / 'shared').resolve()
        self.server.root.mkdir()
        self.server.staging = self.directory.name
        self.server.code = 'ABC12345'
        self.server.token = 'test-session'
        self.server.lock = threading.Lock()
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.directory.cleanup()

    def request(self, method, path, body=None, paired=True):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port)
        headers = {'X-ShareMe': '1'}
        if paired:
            headers['Cookie'] = 'session=test-session'
        connection.request(method, path, body, headers)
        response = connection.getresponse()
        result = response.status, response.read(), response.getheader('Set-Cookie')
        connection.close()
        return result

    def test_pairing_required(self):
        self.assertEqual(self.request('GET', '/api/files', paired=False)[0], 401)
        self.assertEqual(self.request('POST', '/api/pair', json.dumps({'code':'bad'}), False)[0], 403)
        code, _, cookie = self.request('POST', '/api/pair', json.dumps({'code':'ABC12345'}), False)
        self.assertEqual(code, 200)
        self.assertIn('HttpOnly', cookie)

    def test_folder_roundtrip_and_duplicates(self):
        for _ in range(2):
            self.assertEqual(self.request('POST', '/api/upload?path=photos/a.txt', b'hello')[0], 201)
        self.assertTrue((self.server.root / 'photos/a (1).txt').exists())
        self.assertEqual(self.request('GET', '/api/download?path=photos/a.txt')[1], b'hello')
        code, data, _ = self.request('GET', '/api/download?path=photos')
        self.assertEqual(code, 200)
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            self.assertEqual(archive.read('a.txt'), b'hello')
            self.assertEqual(len(archive.namelist()), 2)

    def test_escape_rejected(self):
        self.assertEqual(self.request('POST', '/api/upload?path=../outside', b'bad')[0], 400)
        self.assertEqual(self.request('GET', '/api/download?path=..')[0], 400)
        self.assertFalse((Path(self.directory.name) / 'outside').exists())

    def test_empty_and_unicode_files(self):
        self.assertEqual(self.request('POST', '/api/upload?path=caf%C3%A9.txt', b'')[0], 201)
        self.assertEqual(self.request('GET', '/api/download?path=caf%C3%A9.txt')[1], b'')


if __name__ == '__main__':
    unittest.main()
