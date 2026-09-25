#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "services/core/java/com/android/server/spoof/AxSpoofManager.java"

text = TARGET.read_text(encoding="utf-8")

required = (
    "MAX_KEYBOX_DOWNLOAD_BYTES = 2 * 1024 * 1024",
    "downloadUtf8Bounded(",
    "conn.getContentLengthLong()",
    "total > maxBytes",
    "try (InputStream in = conn.getInputStream();",
    "ByteArrayOutputStream out = new ByteArrayOutputStream",
    "conn.disconnect();",
    "conn.setUseCaches(false);",
)
for token in required:
    if token not in text:
        raise SystemExit(f"Missing keybox I/O invariant: {token}")

method_start = text.find("private static String downloadUtf8Bounded")
method_end = text.find("/** Keybox XML", method_start)
if method_start < 0 or method_end < 0:
    raise SystemExit("Unable to isolate bounded download helper")

helper = text[method_start:method_end]
if "readAllBytes()" in helper:
    raise SystemExit("Bounded downloader must not use readAllBytes()")
if helper.find("getResponseCode()") > helper.find("getInputStream()"):
    raise SystemExit("HTTP status must be checked before reading response body")
if helper.find("total > maxBytes") > helper.find("out.write("):
    raise SystemExit("Size limit must be checked before writing beyond the cap")

refresh_start = text.find("private void refreshKeyboxIfStale()")
refresh_end = text.find("/** Keybox XML", refresh_start)
refresh = text[refresh_start:refresh_end]
if "downloadUtf8Bounded(" not in refresh:
    raise SystemExit("Keybox refresh must use bounded downloader")
if "readAllBytes()" in refresh:
    raise SystemExit("Keybox refresh must not read an unbounded response")

print("Spoof keybox network I/O validation passed")
