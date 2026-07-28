#!/usr/bin/env python3

from __future__ import annotations

from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

import maven_proxy


KOTLIN_MODULE_PATH = (
    "/org/jetbrains/kotlin/kotlin-gradle-plugin/2.2.21/"
    "kotlin-gradle-plugin-2.2.21.module"
)
MISSING_MODULE_PATH = "/example/missing/1.0/missing-1.0.module"
DENIED_MODULE_PATH = "/example/denied/1.0/denied-1.0.module"
KOTLIN_MODULE = b'{"formatVersion":"1.1","variants":[{"name":"gradle813Runtime"}]}'


class _UpstreamHandler(BaseHTTPRequestHandler):
    counts: Counter[str] = Counter()

    def do_GET(self) -> None:  # noqa: N802
        self.counts[self.path] += 1
        if self.path == KOTLIN_MODULE_PATH:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(KOTLIN_MODULE)))
            self.end_headers()
            self.wfile.write(KOTLIN_MODULE)
            return
        if self.path == DENIED_MODULE_PATH:
            self.send_error(403)
            return
        self.send_error(404)

    def log_message(self, fmt: str, *args: object) -> None:
        pass


class MavenProxyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.cache_root = Path(self.temporary.name)
        _UpstreamHandler.counts.clear()
        self.upstream = ThreadingHTTPServer(("127.0.0.1", 0), _UpstreamHandler)
        self.upstream_thread = threading.Thread(
            target=self.upstream.serve_forever,
            daemon=True,
        )
        self.upstream_thread.start()
        self.original_central = maven_proxy.UPSTREAMS["central"]
        maven_proxy.UPSTREAMS["central"] = (
            f"http://127.0.0.1:{self.upstream.server_port}"
        )
        self.original_no_proxy = {
            name: os.environ.get(name)
            for name in ("NO_PROXY", "no_proxy")
        }
        os.environ["NO_PROXY"] = "127.0.0.1,localhost"
        os.environ["no_proxy"] = "127.0.0.1,localhost"

        self.proxy = ThreadingHTTPServer(
            ("127.0.0.1", 0),
            maven_proxy.MavenProxyHandler,
        )
        self.proxy.cache_root = self.cache_root
        self.proxy_thread = threading.Thread(
            target=self.proxy.serve_forever,
            daemon=True,
        )
        self.proxy_thread.start()

    def tearDown(self) -> None:
        self.proxy.shutdown()
        self.proxy.server_close()
        self.proxy_thread.join(timeout=2)
        maven_proxy.UPSTREAMS["central"] = self.original_central
        for name, value in self.original_no_proxy.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value
        self.upstream.shutdown()
        self.upstream.server_close()
        self.upstream_thread.join(timeout=2)
        self.temporary.cleanup()

    def test_kotlin_module_metadata_is_fetched_and_positively_cached(self) -> None:
        url = self.proxy_url(KOTLIN_MODULE_PATH)

        self.assertEqual(KOTLIN_MODULE, self.read(url))
        self.assertEqual(KOTLIN_MODULE, self.read(url))

        self.assertEqual(1, _UpstreamHandler.counts[KOTLIN_MODULE_PATH])
        self.assertEqual(
            KOTLIN_MODULE,
            (self.cache_root / "central" / KOTLIN_MODULE_PATH.lstrip("/")).read_bytes(),
        )
        self.assertFalse(self.negative_marker(KOTLIN_MODULE_PATH).exists())

    def test_real_module_404_is_cached_without_fabricating_success(self) -> None:
        url = self.proxy_url(MISSING_MODULE_PATH)

        self.assertHttpError(404, lambda: self.read(url))
        self.assertHttpError(404, lambda: self.read(url))

        self.assertEqual(1, _UpstreamHandler.counts[MISSING_MODULE_PATH])
        self.assertTrue(self.negative_marker(MISSING_MODULE_PATH).is_file())

    def test_non_404_error_is_not_fabricated_as_or_cached_like_404(self) -> None:
        url = self.proxy_url(DENIED_MODULE_PATH)

        self.assertHttpError(403, lambda: self.read(url))
        self.assertHttpError(403, lambda: self.read(url))

        self.assertEqual(2, _UpstreamHandler.counts[DENIED_MODULE_PATH])
        self.assertFalse(self.negative_marker(DENIED_MODULE_PATH).exists())

    def test_http_status_parser_rejects_missing_or_malformed_status(self) -> None:
        self.assertEqual(200, maven_proxy._parse_http_status("200"))
        self.assertEqual(404, maven_proxy._parse_http_status(" 404\n"))
        self.assertIsNone(maven_proxy._parse_http_status(""))
        self.assertIsNone(maven_proxy._parse_http_status("000"))
        self.assertIsNone(maven_proxy._parse_http_status("200200"))
        self.assertIsNone(maven_proxy._parse_http_status("abc"))

    def proxy_url(self, upstream_path: str) -> str:
        return (
            f"http://127.0.0.1:{self.proxy.server_port}/central"
            f"{upstream_path}"
        )

    @staticmethod
    def read(url: str) -> bytes:
        with urllib.request.urlopen(url, timeout=5) as response:
            return response.read()

    def negative_marker(self, upstream_path: str) -> Path:
        target = self.cache_root / "central" / upstream_path.lstrip("/")
        return target.with_name(f"{target.name}.not-found")

    def assertHttpError(self, status: int, action) -> None:
        with self.assertRaises(urllib.error.HTTPError) as caught:
            action()
        self.assertEqual(status, caught.exception.code)


if __name__ == "__main__":
    unittest.main()
