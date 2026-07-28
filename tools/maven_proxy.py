#!/usr/bin/env python3
"""Small read-only Maven cache proxy for restricted development containers.

Normal local machines and GitHub Actions do not need this. Set
SENSE_MAVEN_PROXY=http://127.0.0.1:18999 while this process is running.
"""

from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import mimetypes
import os
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit


UPSTREAMS = {
    "google": "https://dl.google.com/dl/android/maven2",
    "central": "https://repo1.maven.org/maven2",
    "plugins": "https://plugins.gradle.org/m2",
}

DOWNLOAD_SLOTS = threading.BoundedSemaphore(4)
PATH_LOCKS: defaultdict[str, threading.Lock] = defaultdict(threading.Lock)


class MavenProxyHandler(BaseHTTPRequestHandler):
    server_version = "SenseMavenProxy/1"

    def do_HEAD(self) -> None:  # noqa: N802
        self._serve(send_body=False)

    def do_GET(self) -> None:  # noqa: N802
        self._serve(send_body=True)

    def _serve(self, send_body: bool) -> None:
        parsed = urlsplit(self.path)
        parts = [part for part in unquote(parsed.path).split("/") if part]
        if len(parts) < 2 or parts[0] not in UPSTREAMS or any(part == ".." for part in parts):
            self.send_error(404)
            return

        repository, relative_parts = parts[0], parts[1:]
        relative = "/".join(relative_parts)
        upstream = f"{UPSTREAMS[repository]}/{relative}"
        cache_root: Path = self.server.cache_root  # type: ignore[attr-defined]
        cache_path = cache_root / repository / Path(*relative_parts)
        negative_path = cache_path.with_name(f"{cache_path.name}.not-found")

        if negative_path.is_file():
            self.send_error(404)
            return

        with PATH_LOCKS[str(cache_path)]:
            # A peer request may have learned the real 404 while this request
            # waited for the per-coordinate lock.
            if negative_path.is_file():
                self.send_error(404)
                return
            if not cache_path.is_file():
                cache_path.parent.mkdir(parents=True, exist_ok=True)
                fd, temporary_name = tempfile.mkstemp(prefix="sense-maven-", dir=cache_path.parent)
                os.close(fd)
                temporary = Path(temporary_name)
                try:
                    with DOWNLOAD_SLOTS:
                        result = subprocess.run(
                            [
                                "curl",
                                "-L",
                                "--fail",
                                "--silent",
                                "--show-error",
                                "--connect-timeout",
                                "5",
                                "--max-time",
                                "30",
                                "--retry",
                                "3",
                                "--retry-connrefused",
                                "--write-out",
                                "%{http_code}",
                                "-o",
                                str(temporary),
                                upstream,
                            ],
                            check=False,
                            stdout=subprocess.PIPE,
                            text=True,
                            timeout=140,
                        )
                    if result.returncode != 0:
                        temporary.unlink(missing_ok=True)
                        upstream_status = _parse_http_status(result.stdout)
                        if upstream_status == 404:
                            negative_path.touch()
                        self.send_error(
                            upstream_status
                            if upstream_status is not None
                            else 503
                        )
                        return
                    upstream_status = _parse_http_status(result.stdout)
                    if upstream_status is None or not 200 <= upstream_status < 300:
                        temporary.unlink(missing_ok=True)
                        self.send_error(502)
                        return
                    os.replace(temporary, cache_path)
                except (OSError, subprocess.TimeoutExpired):
                    temporary.unlink(missing_ok=True)
                    self.send_error(502)
                    return

        size = cache_path.stat().st_size
        content_type = mimetypes.guess_type(cache_path.name)[0] or "application/octet-stream"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(size))
        self.send_header("ETag", hashlib.sha256(cache_path.read_bytes()).hexdigest())
        self.end_headers()
        if send_body:
            with cache_path.open("rb") as source:
                while chunk := source.read(1024 * 256):
                    self.wfile.write(chunk)

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"{self.address_string()} {fmt % args}", flush=True)


def _parse_http_status(value: str) -> int | None:
    candidate = value.strip()
    if len(candidate) != 3 or not candidate.isdigit():
        return None
    status = int(candidate)
    return status if 100 <= status <= 599 else None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18999)
    parser.add_argument("--cache", type=Path, default=Path("/workspace/toolchains/maven-proxy-cache"))
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)

    server = ThreadingHTTPServer((args.host, args.port), MavenProxyHandler)
    server.cache_root = args.cache  # type: ignore[attr-defined]
    print(f"Sense Maven proxy listening on http://{args.host}:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
