#!/usr/bin/env python3
"""Produces the screenshots this project publishes, against a server invented for the purpose.

Every screen this app publishes names the server it is talking to: the Account card carries the
Account's label, a Collection's screen carries its URL, and the log carries the Origin on every line.
A screenshot taken against a real server therefore publishes that server — which is how a private
host ended up on the site, in the README and in git history, where deleting the file does not remove
it.

So this is the only supported way to produce one. It stands up a DAV server that answers as
`dav.example.com`, teaches a throwaway emulator to resolve and trust it, configures an Account
against it, drives the app, and — this is the part that matters — reads what is actually on the
screen before each write and refuses to save an image that names anything else.

    python3 tools/screenshots/capture.py --apk path/to/app-debug.apk

Requires: adb, docker, python3 with Pillow, openssl, and an emulator that `adb root` succeeds on.
See README.md beside this file.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import ipaddress
import json
import os
import re
import shutil
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
FIXTURE = HERE / "fixture"
MANIFEST = json.loads((FIXTURE / "manifest.json").read_text())

HOST = MANIFEST["host"]
PORT = MANIFEST["port"]
BASE = f"https://{HOST}:{PORT}/{MANIFEST['username']}/"
CONTAINER = "davkeep-demo"
RADICALE_PORT = 5233
PACKAGE = "app.davkeep"

# The emulator reaches the machine running it at this address; the hosts entry points the demo host
# at it so that the URL the app stores, and shows, is the documentation name.
EMULATOR_HOST_ALIAS = "10.0.2.2"

PERMISSIONS = [
    "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALENDAR", "WRITE_CALENDAR", "POST_NOTIFICATIONS",
]

# What the published screens are allowed to say. `example.com` covers the demo host and the fixture's
# e-mail addresses; the rest are dotted strings this app's own UI shows that are not hosts at all.
# Anything else dotted is treated as a host and refused — failing closed, because a new leak looks
# exactly like a string nobody listed here.
ALLOWED_HOST_SUFFIXES = ("example.com",)
ALLOWED_NON_HOSTS = {
    "app.davkeep",
    "sync-log.txt",
    "vnd.android.cursor.item",
    "davkeep.app",
}
HOSTISH = re.compile(r"\b[a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)+\b", re.IGNORECASE)


def log(message: str) -> None:
    print(f"  {message}", flush=True)


def step(message: str) -> None:
    print(f"\n== {message}", flush=True)


class Refused(Exception):
    """A screen named something that is not the demo host, so no image was written."""


@dataclass
class Adb:
    serial: str

    def run(self, *args: str, check: bool = False, timeout: int = 120) -> str:
        proc = subprocess.run(
            ["adb", "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout
        )
        if check and proc.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {proc.stderr.strip()}")
        return proc.stdout

    def shell(self, command: str, check: bool = False, timeout: int = 120) -> str:
        return self.run("shell", command, check=check, timeout=timeout)

    def dump(self) -> ET.Element:
        """The accessibility tree of whatever is on screen, which is what the guard reads."""
        for _ in range(4):
            self.shell("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
            raw = self.run("shell", "cat", "/sdcard/ui.xml")
            if raw.strip().startswith("<"):
                return ET.fromstring(raw)
            time.sleep(1)
        raise RuntimeError("uiautomator would not produce a dump")

    def tap(self, bounds: str) -> None:
        left, top = bounds.split("][")[0].strip("[").split(",")
        right, bottom = bounds.split("][")[1].strip("]").split(",")
        self.shell(f"input tap {(int(left) + int(right)) // 2} {(int(top) + int(bottom)) // 2}")

    def type(self, text: str) -> None:
        escaped = text.replace("/", r"\/").replace(":", r"\:").replace("-", r"\-").replace(".", r"\.")
        self.run("shell", "input", "text", escaped)

    def nodes(self, root: ET.Element | None = None):
        return list((root if root is not None else self.dump()).iter("node"))

    def by_id(self, suffix: str, root: ET.Element | None = None) -> list[ET.Element]:
        return [n for n in self.nodes(root) if n.attrib.get("resource-id", "").endswith(suffix)]

    def wait_for_id(self, suffix: str, seconds: int = 30) -> ET.Element:
        deadline = time.time() + seconds
        while time.time() < deadline:
            found = self.by_id(suffix)
            if found:
                return found[0]
            time.sleep(1)
        raise RuntimeError(f"never saw {suffix} on screen")


# --------------------------------------------------------------------------- the demo server


def openssl(*args: str, cwd: Path) -> None:
    subprocess.run(["openssl", *args], cwd=cwd, check=True, capture_output=True)


def make_certificate(work: Path) -> tuple[Path, Path, str]:
    """A CA and a leaf for the demo host, generated per run and discarded with it.

    Generated rather than checked in: a private key in a public repository is a private key that has
    been published, even one that only ever signs a name nobody can reach.
    """
    openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "ca.key", "-out", "ca.pem",
            "-days", "3650", "-subj", "/CN=DAVKeep demo CA/O=Example", cwd=work)
    openssl("req", "-newkey", "rsa:2048", "-nodes", "-keyout", "srv.key", "-out", "srv.csr",
            "-subj", f"/CN={HOST}/O=Example", cwd=work)
    (work / "ext.cnf").write_text(
        f"subjectAltName=DNS:{HOST}\nbasicConstraints=CA:FALSE\n"
        "keyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n")
    openssl("x509", "-req", "-in", "srv.csr", "-CA", "ca.pem", "-CAkey", "ca.key", "-CAcreateserial",
            "-out", "srv.pem", "-days", "3650", "-extfile", "ext.cnf", cwd=work)
    (work / "chain.pem").write_bytes((work / "srv.pem").read_bytes() + (work / "srv.key").read_bytes())
    # The CA key has signed the one leaf it will ever sign. Kept, it is a key that any device trusting
    # this run's CA would accept a forged certificate from, for as long as the file survives — and
    # --keep lets it survive.
    (work / "ca.key").unlink()
    # Android names a certificate in its store by the old-style subject hash.
    digest = subprocess.run(["openssl", "x509", "-in", "ca.pem", "-noout", "-subject_hash_old"],
                            cwd=work, capture_output=True, text=True, check=True).stdout.strip()
    return work / "ca.pem", work / "chain.pem", digest


def start_radicale(work: Path) -> None:
    data = work / "radicale"
    (data / "collections").mkdir(parents=True)
    (data / "users").write_text(f"{MANIFEST['username']}:{MANIFEST['password']}\n")
    # The image expects /config/config to be a file, so the config is mounted as one.
    (work / "radicale.conf").write_text(
        "[server]\nhosts = 0.0.0.0:5232\n[auth]\ntype = htpasswd\n"
        "htpasswd_filename = /data/users\nhtpasswd_encryption = plain\n"
        "[storage]\nfilesystem_folder = /data/collections\n[rights]\ntype = owner_only\n")
    data.chmod(0o777)
    for path in data.rglob("*"):
        path.chmod(0o777)
    subprocess.run(["docker", "rm", "-f", CONTAINER], capture_output=True)
    subprocess.run(
        ["docker", "run", "-d", "--name", CONTAINER, "-p", f"127.0.0.1:{RADICALE_PORT}:5232",
         "-v", f"{data}:/data", "-v", f"{work / 'radicale.conf'}:/config/config:ro",
         "tomsquest/docker-radicale:latest"],
        check=True, capture_output=True)
    for _ in range(40):
        try:
            request("PROPFIND", f"http://127.0.0.1:{RADICALE_PORT}/{MANIFEST['username']}/", depth="0")
            return
        except Exception:
            time.sleep(0.5)
    raise RuntimeError("the demo server did not come up")


def request(method: str, url: str, body: bytes | None = None, depth: str | None = None,
            content_type: str | None = None) -> int:
    credentials = base64.b64encode(
        f"{MANIFEST['username']}:{MANIFEST['password']}".encode()).decode()
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", f"Basic {credentials}")
    if depth:
        req.add_header("Depth", depth)
    if content_type:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status
    except urllib.error.HTTPError as e:
        return e.code


def seed_fixture() -> None:
    """Puts the checked-in Collections and items on the demo server.

    Restored on every run rather than kept in a volume, so that two captures a month apart produce
    the same screens and a diff of the images is a UI change rather than data drift.
    """
    root = f"http://127.0.0.1:{RADICALE_PORT}/{MANIFEST['username']}/"
    for collection in MANIFEST["collections"]:
        kind = collection["type"]
        colour = collection.get("colour")
        if kind == "addressbook":
            resource = '<D:collection/><CR:addressbook/>'
            namespaces = 'xmlns:CR="urn:ietf:params:xml:ns:carddav"'
            extra = ""
        else:
            resource = '<D:collection/><C:calendar/>'
            namespaces = 'xmlns:C="urn:ietf:params:xml:ns:caldav"'
            extra = (f'<I:calendar-color xmlns:I="http://apple.com/ns/ical/">{colour}</I:calendar-color>'
                     if colour else "")
        body = (f'<?xml version="1.0" encoding="utf-8"?><D:mkcol xmlns:D="DAV:" {namespaces}>'
                f'<D:set><D:prop><D:resourcetype>{resource}</D:resourcetype>'
                f'<D:displayname>{collection["displayname"]}</D:displayname>{extra}'
                "</D:prop></D:set></D:mkcol>").encode()
        status = request("MKCOL", root + collection["path"] + "/", body, content_type="application/xml")
        if status not in (201, 405):
            raise RuntimeError(f"MKCOL {collection['path']} answered {status}")
        source = FIXTURE / collection["items"]
        for item in sorted(source.iterdir()):
            media = "text/vcard" if item.suffix == ".vcf" else "text/calendar"
            status = request("PUT", root + collection["path"] + "/" + item.name,
                             item.read_bytes(), content_type=media)
            if status not in (201, 204):
                raise RuntimeError(f"PUT {item.name} answered {status}")
        log(f"{collection['displayname']}: {len(list(source.iterdir()))} items")


FRONT = '''
import ssl, sys, urllib.error, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
UPSTREAM = "http://127.0.0.1:%d"
HOP = {"connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade"}
class Front(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, fmt, *args): pass
    def relay(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        req = urllib.request.Request(UPSTREAM + self.path, data=body, method=self.command)
        for name, value in self.headers.items():
            if name.lower() not in HOP and name.lower() != "host":
                req.add_header(name, value)
        try:
            with urllib.request.urlopen(req) as up:
                payload, status, headers = up.read(), up.status, up.headers
        except urllib.error.HTTPError as e:
            payload, status, headers = e.read(), e.code, e.headers
        payload = payload.replace(b"http://127.0.0.1:%d", b"https://%s:%d")
        self.send_response(status)
        for name, value in headers.items():
            if name.lower() not in HOP and name.lower() != "content-length":
                self.send_header(name, value)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)
    def __getattr__(self, name):
        if name.startswith("do_"): return self.relay
        raise AttributeError(name)
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(sys.argv[1])
srv = ThreadingHTTPServer(("127.0.0.1", %d), Front)
srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
srv.serve_forever()
'''


def start_front(work: Path, chain: Path) -> subprocess.Popen:
    """Terminates TLS as the demo host and rewrites the URLs Radicale reports about itself."""
    script = work / "front.py"
    script.write_text(FRONT % (RADICALE_PORT, RADICALE_PORT, HOST.encode().decode(), PORT, PORT))
    process = subprocess.Popen([sys.executable, str(script), str(chain)],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(40):
        try:
            context = ssl.create_default_context()
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
            with urllib.request.urlopen(f"https://127.0.0.1:{PORT}/", context=context, timeout=2):
                pass
            return process
        except urllib.error.HTTPError:
            return process
        except Exception:
            time.sleep(0.5)
    raise RuntimeError("the TLS front did not come up")


# --------------------------------------------------------------------------- the emulator


def require_emulator(adb: Adb) -> None:
    """Refuses a real phone before anything is changed on it.

    What follows uninstalls the app with every Account on it, and mounts a CA over the system trust
    store. `adb root` failing used to be the only thing standing between that and a phone, and it
    succeeds on a userdebug build or with rooted debugging turned on. An emulator says so in a
    property no phone sets.
    """
    qemu = (adb.shell("getprop ro.kernel.qemu").strip(), adb.shell("getprop ro.boot.qemu").strip())
    if not adb.serial.startswith("emulator-") or "1" not in qemu:
        raise RuntimeError(f"{adb.serial} is not an emulator; refusing to touch its trust store or apps")


def prepare_emulator(adb: Adb, ca: Path, digest: str) -> None:
    """Teaches the emulator to resolve and trust the demo host.

    Three facts, each of which presents as "Couldn't reach the server" when missed:

    - The app declares no network security configuration, so it trusts the system store only. A
      user-installed CA is not enough.
    - On current API levels the runtime store is the one inside the Conscrypt APEX, and an app does
      not share the mount namespace of the shell that mounts over it. The bind has to happen in
      init's namespace, and the framework has to be restarted so that everything inherits it.
    - The files behind those mounts must carry a system SELinux label. Without it the resolver and
      the trust store ignore them silently, which looks exactly like a network failure.
    """
    if "cannot run as root" in adb.run("root"):
        raise RuntimeError("this emulator will not give adb root; use a Google APIs image, not Play")
    time.sleep(3)
    adb.run("wait-for-device")
    adb.run("push", str(ca), f"/data/local/tmp/{digest}.0", check=True)
    prepare = f"""
    set -e
    # A previous run's binds are still in place, and copying the store onto itself through one of
    # them fails. Undo them first so the sources are the platform's own again.
    nsenter -t 1 -m -- umount /apex/com.android.conscrypt/cacerts 2>/dev/null || true
    nsenter -t 1 -m -- umount /system/etc/security/cacerts 2>/dev/null || true
    nsenter -t 1 -m -- umount /system/etc/hosts 2>/dev/null || true
    rm -rf /data/local/tmp/cacerts
    mkdir -p /data/local/tmp/cacerts
    cp /apex/com.android.conscrypt/cacerts/* /data/local/tmp/cacerts/ 2>/dev/null || \
      cp /system/etc/security/cacerts/* /data/local/tmp/cacerts/
    cp /data/local/tmp/{digest}.0 /data/local/tmp/cacerts/
    cp /system/etc/hosts /data/local/tmp/hosts
    grep -q {HOST} /data/local/tmp/hosts || printf '{EMULATOR_HOST_ALIAS} {HOST}\\n' >> /data/local/tmp/hosts
    chmod 644 /data/local/tmp/hosts /data/local/tmp/cacerts/*
    chown root:root /data/local/tmp/hosts /data/local/tmp/cacerts/*
    chcon u:object_r:system_file:s0 /data/local/tmp/hosts /data/local/tmp/cacerts
    chcon u:object_r:system_security_cacerts_file:s0 /data/local/tmp/cacerts/* 2>/dev/null || \
      chcon u:object_r:system_file:s0 /data/local/tmp/cacerts/*
    nsenter -t 1 -m -- mount --bind /data/local/tmp/cacerts /apex/com.android.conscrypt/cacerts 2>/dev/null || \
      nsenter -t 1 -m -- mount --bind /data/local/tmp/cacerts /system/etc/security/cacerts
    nsenter -t 1 -m -- mount --bind /data/local/tmp/hosts /system/etc/hosts
    killall netd
    echo PREPARED
    """
    if "PREPARED" not in adb.shell(prepare):
        raise RuntimeError("could not install the demo host's name and certificate")
    for _ in range(20):
        time.sleep(2)
        if EMULATOR_HOST_ALIAS in adb.shell(f"ping -c 1 -W 2 {HOST}"):
            log(f"{HOST} resolves and its CA is trusted")
            return
    raise RuntimeError(f"{HOST} still does not resolve on the device")


def install(adb: Adb, apk: Path) -> str:
    """Uninstalls first: that is what takes any Account left by earlier work with it."""
    adb.run("uninstall", PACKAGE)
    if adb.shell(f"pm path {PACKAGE}").strip():
        raise RuntimeError(f"{PACKAGE} is still installed after uninstalling it; its Accounts would survive")
    out = adb.run("install", str(apk))
    if "Success" not in out:
        raise RuntimeError(f"install failed: {out.strip()}")
    for permission in PERMISSIONS:
        adb.shell(f"pm grant {PACKAGE} android.permission.{permission}")
    version = re.search(r"versionName=(\S+)", adb.shell(f"dumpsys package {PACKAGE}"))
    code = re.search(r"versionCode=(\d+)", adb.shell(f"dumpsys package {PACKAGE}"))
    return f"{version.group(1) if version else '?'} ({code.group(1) if code else '?'})"


# --------------------------------------------------------------------------- driving the app


def open_settings(adb: Adb) -> None:
    adb.shell(f"am force-stop {PACKAGE}")
    adb.shell(f"am start -n {PACKAGE}/.ui.SettingsActivity")
    time.sleep(4)


def add_account(adb: Adb, label: str | None = None) -> None:
    adb.tap(adb.wait_for_id("add_account").attrib["bounds"])
    time.sleep(3)
    adb.tap(adb.wait_for_id("url_input").attrib["bounds"])
    time.sleep(0.5)
    adb.type(BASE)
    if label:
        time.sleep(0.5)
        adb.tap(adb.wait_for_id("label_input").attrib["bounds"])
        time.sleep(0.5)
        adb.shell("input keyevent 123")
        for _ in range(48):
            adb.shell("input keyevent 67")
        adb.type(label)
    adb.shell("input keyevent 111")
    time.sleep(0.8)
    adb.tap(adb.wait_for_id("setup_advanced_row").attrib["bounds"])
    time.sleep(1.5)
    adb.shell("input swipe 540 1600 540 600 300")
    time.sleep(1.2)
    adb.tap(adb.wait_for_id("username_input").attrib["bounds"])
    time.sleep(0.5)
    adb.type(MANIFEST["username"])
    adb.shell("input keyevent 111")
    time.sleep(0.8)
    adb.tap(adb.wait_for_id("password_input").attrib["bounds"])
    time.sleep(0.5)
    adb.type(MANIFEST["password"])
    adb.shell("input keyevent 111")
    time.sleep(0.8)
    for _ in range(4):
        adb.shell("input swipe 540 1700 540 700 200")
    time.sleep(1)
    adb.tap(adb.wait_for_id("save_account").attrib["bounds"])
    time.sleep(12)


def discover_and_select(adb: Adb) -> None:
    """Discovery, then the Collection states the published screens are meant to show."""
    adb.tap(adb.wait_for_id("account_refresh_collections").attrib["bounds"])
    time.sleep(16)
    for node in adb.nodes():
        if (node.attrib.get("text") or "") == "Close":
            adb.tap(node.attrib["bounds"])
            break
    time.sleep(2)

    wanted = {c["displayname"]: c for c in MANIFEST["collections"]}
    root = adb.dump()
    names = [n.attrib.get("text") for n in adb.by_id("collection_name", root)]
    boxes = [n.attrib["bounds"] for n in adb.by_id("collection_selected", root)]
    for name, box in zip(names, boxes):
        if wanted.get(name, {}).get("selected"):
            adb.tap(box)
            time.sleep(1.5)

    for index, name in enumerate(names):
        if not wanted.get(name, {}).get("writable"):
            continue
        adb.tap(adb.by_id("collection_open")[index].attrib["bounds"])
        time.sleep(3)
        adb.tap(adb.wait_for_id("collection_writable").attrib["bounds"])
        time.sleep(2)
        adb.shell("input keyevent 4")
        time.sleep(2)

    for _ in range(2):
        adb.tap(adb.wait_for_id("account_sync_now").attrib["bounds"])
        time.sleep(22)


# --------------------------------------------------------------------------- the guard


# The host of anything written as a URL, bracketed IPv6 included. A URL names a host whatever that
# host looks like, which is what catches the shapes a dotted-word sweep cannot: `https://luna:8443/`.
URL_HOST = re.compile(r"[a-z][a-z0-9+.-]*://(?:[^/\s@]*@)?(\[[^\]]+\]|[^/\s:?#]+)", re.IGNORECASE)
IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
IPV6 = re.compile(r"(?<![\w:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![\w:])", re.IGNORECASE)

# Widgets that carry a host by construction, so it is a host whatever its shape. An Account's label
# defaults to the host of its URL — `luna` for `https://luna:8443/`, an address for an IP — and the log
# opens every entry with that label, followed by the Collection's type: `luna  contacts`. A
# single-label name has no dot for the dotted sweep to find, and it is the usual shape of a home or
# tailnet server: the host that leaked in the first place was one.
HOST_FIELDS = {"account_label": "whole", "log_detail": "first-token"}


def _is_ip(token: str) -> bool:
    try:
        ipaddress.ip_address(token.strip("[]"))
        return True
    except ValueError:
        return False


def hosts_named_on(root: ET.Element) -> set[str]:
    """Every host the screen is showing, whatever widget it sits in and whatever shape it has.

    Reads the accessibility tree rather than the pixels. Four sources, because each catches a shape
    the others miss: the host of anything written as a URL; IP literals; the widgets whose text is a
    host by construction; and, for everything else, dotted words — which is what covers a screen
    added later without this having to learn about it.
    """
    found: set[str] = set()
    for node in root.iter("node"):
        rid = node.attrib.get("resource-id", "").rsplit("/", 1)[-1]
        for attribute in ("text", "content-desc"):
            value = node.attrib.get(attribute) or ""
            if not value:
                continue

            for host in URL_HOST.findall(value):
                found.add(host.lower().strip("[].").rstrip("."))
            for literal in IPV4.findall(value) + IPV6.findall(value):
                if _is_ip(literal):
                    found.add(literal.lower())

            role = HOST_FIELDS.get(rid)
            if attribute == "text" and role:
                field_text = value.strip() if role == "whole" else (value.split() or [""])[0]
                field_text = field_text.lower().rstrip(".")
                if field_text:
                    found.add(field_text)

            for candidate in HOSTISH.findall(value):
                token = candidate.lower().strip(".")
                if token in ALLOWED_NON_HOSTS:
                    continue
                # A version number is dotted and is not a host; an IPv4 address is caught above, so
                # only dotted numbers that are not one are skipped here.
                if all(part.isdigit() for part in token.split(".")) and not _is_ip(token):
                    continue
                found.add(token)
    return found


def offending(hosts: set[str]) -> set[str]:
    return {h for h in hosts if not any(h == s or h.endswith("." + s) for s in ALLOWED_HOST_SUFFIXES)}


def assert_only_demo_account(root: ET.Element) -> None:
    """The Account list must hold this run's Account and nothing else.

    A leftover Account from earlier work is not caught by the host check — one was labelled after a
    host, but a disconnected Account can be labelled anything — and it ended up in a published
    image once already.
    """
    labels = [n.attrib.get("text") for n in root.iter("node")
              if n.attrib.get("resource-id", "").endswith("account_label")]
    if labels and labels != [HOST]:
        raise Refused(f"the Account list holds {labels}, not just {HOST}")


def capture(adb: Adb, name: str, out: Path, also: "callable | None" = None) -> Path:
    """Reads the screen, refuses if it names anything but the demo host, and only then saves it."""
    root = adb.dump()
    named = hosts_named_on(root)
    bad = offending(named)
    if bad:
        raise Refused(f"{name}: the screen names {sorted(bad)}, which is not {HOST}")
    if also:
        also(root)
    out.parent.mkdir(parents=True, exist_ok=True)
    raw = subprocess.run(["adb", "-s", adb.serial, "exec-out", "screencap", "-p"],
                         capture_output=True, check=True).stdout
    out.write_bytes(raw)
    log(f"{name}: names {sorted(named) or 'no host'} -> {out.name}")
    return out


SCREENS = ["accounts", "collection", "setup", "log"]


def capture_all(adb: Adb, out: Path) -> dict[str, Path]:
    written: dict[str, Path] = {}

    open_settings(adb)
    written["accounts"] = capture(adb, "accounts", out / "accounts.png", also=assert_only_demo_account)

    # The Collection screen is published to show what the switches mean, so it opens the one the
    # fixture makes writable rather than whichever row the server happened to list first.
    root = adb.dump()
    names = [n.attrib.get("text") for n in adb.by_id("collection_name", root)]
    writable = next(c["displayname"] for c in MANIFEST["collections"] if c["writable"])
    adb.tap(adb.by_id("collection_open", root)[names.index(writable)].attrib["bounds"])
    time.sleep(3)
    written["collection"] = capture(adb, "collection", out / "collection.png")
    adb.shell("input keyevent 4")
    time.sleep(2)

    adb.tap(adb.wait_for_id("add_account").attrib["bounds"])
    time.sleep(3)
    adb.tap(adb.wait_for_id("setup_advanced_row").attrib["bounds"])
    time.sleep(2)
    adb.shell("input swipe 540 1500 540 1150 250")
    time.sleep(1.5)
    written["setup"] = capture(adb, "setup", out / "setup.png")
    adb.shell("input keyevent 4")
    time.sleep(2)

    adb.tap(adb.wait_for_id("view_log").attrib["bounds"])
    time.sleep(4)
    written["log"] = capture(adb, "log", out / "log.png")
    adb.shell("input keyevent 4")
    return written


# --------------------------------------------------------------------------- publishing


def publish(written: dict[str, Path], version: str, site_dir: Path | None) -> None:
    """Writes each consumer's copy: the listing wants device resolution, the site and README less."""
    from PIL import Image

    listing = REPO / "fastlane/metadata/android/en-US/images/phoneScreenshots"
    readme = REPO / "docs/shots"
    listing.mkdir(parents=True, exist_ok=True)
    readme.mkdir(parents=True, exist_ok=True)
    record = {"version": version, "captured": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
              "host": HOST, "images": {}}

    for index, name in enumerate(SCREENS, start=1):
        image = Image.open(written[name]).convert("RGB")
        image.save(listing / f"{index}.png")
        image.resize((image.width // 3, image.height // 3), Image.LANCZOS).save(readme / f"{index}.png")
        if site_dir:
            site_dir.mkdir(parents=True, exist_ok=True)
            image.resize((image.width // 2, image.height // 2), Image.LANCZOS).save(
                site_dir / f"{name}.webp", quality=82, method=6)
        record["images"][name] = hashlib.sha256((listing / f"{index}.png").read_bytes()).hexdigest()
    (HERE / "last-capture.json").write_text(json.dumps(record, indent=2) + "\n")
    log(f"published {len(SCREENS)} screens taken on {version}")


def teardown(front: subprocess.Popen | None, keep: bool, work: Path) -> None:
    """Stops everything this run started, unless it was asked to leave the fixture up.

    Kept means kept whole: the device checks the fixture exists for reach it as the demo host over
    TLS, so the front has to outlive this script too, not just the server behind it.
    """
    if keep:
        pid = front.pid if front else "?"
        log(f"left running: container {CONTAINER}, TLS front pid {pid}, files in {work}")
        log(f"stop with: kill {pid}; docker rm -f {CONTAINER}; rm -rf {work}")
        return
    if front:
        front.terminate()
    # Radicale writes its collections as the container's user, which this script cannot delete, so
    # the container removes them itself before it goes. Without this every run left its server data
    # in the temp directory, and ignoring the errors was what kept that from being noticed.
    subprocess.run(["docker", "exec", CONTAINER, "rm", "-rf", "/data/collections"], capture_output=True)
    subprocess.run(["docker", "rm", "-f", CONTAINER], capture_output=True)
    try:
        shutil.rmtree(work)
    except OSError as e:
        log(f"could not remove {work}: {e}")


# --------------------------------------------------------------------------- entry point


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apk", required=True, type=Path, help="the build to photograph")
    parser.add_argument("--serial", default=None, help="adb serial; the only emulator by default")
    parser.add_argument("--site-dir", type=Path, default=None,
                        help="a gh-pages checkout's image directory, to write the site copies into")
    parser.add_argument("--keep", action="store_true",
                        help="leave the demo server and its TLS front running, for device checks")
    parser.add_argument("--prove-refusal", action="store_true",
                        help="configure an Account labelled after another host and require the "
                             "capture to refuse: a guard that has never fired is not known to work")
    args = parser.parse_args()

    serial = args.serial
    if not serial:
        devices = [l.split()[0] for l in subprocess.run(["adb", "devices"], capture_output=True, text=True)
                   .stdout.splitlines()[1:] if "\tdevice" in l and l.startswith("emulator-")]
        if len(devices) != 1:
            print(f"give --serial: adb sees {devices or 'no emulators'}", file=sys.stderr)
            return 2
        serial = devices[0]
    adb = Adb(serial)

    work = Path(tempfile.mkdtemp(prefix="davkeep-demo-"))
    front = None
    try:
        step("Demo server")
        ca, chain, digest = make_certificate(work)
        start_radicale(work)
        seed_fixture()
        front = start_front(work, chain)
        log(f"{BASE} is up, with a CA that exists only for this run")

        step(f"Emulator {serial}")
        require_emulator(adb)
        prepare_emulator(adb, ca, digest)
        version = install(adb, args.apk)
        log(f"installed {PACKAGE} {version}")

        step("Account")
        open_settings(adb)
        label = "example.org" if args.prove_refusal else None
        add_account(adb, label=label)
        discover_and_select(adb)
        log("Collections discovered, selected and synced")

        step("Screens")
        shots = work / "shots"
        try:
            written = capture_all(adb, shots)
        except Refused as refusal:
            if args.prove_refusal:
                saved = list(shots.glob("*.png"))
                if saved:
                    print(f"\nFAIL: refused, but had already written {saved}", file=sys.stderr)
                    return 1
                print(f"\nRefused as it should: {refusal}")
                print("No image was written. The guard works.")
                return 0
            print(f"\nREFUSED: {refusal}", file=sys.stderr)
            print("No image was written. Fix what the screen is naming, then run again.", file=sys.stderr)
            return 1
        if args.prove_refusal:
            print("\nFAIL: the capture wrote images for an Account named after another host",
                  file=sys.stderr)
            return 1

        step("Publish")
        publish(written, version, args.site_dir)
        return 0
    finally:
        teardown(front, args.keep, work)


if __name__ == "__main__":
    sys.exit(main())
