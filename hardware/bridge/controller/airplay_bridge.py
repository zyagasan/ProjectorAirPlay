#!/usr/bin/env python3
"""AirPlay Bridge 管理デーモン(Web 画面つき)。Python 標準ライブラリだけで動く。

- Bluetooth(BlueALSA)にプロジェクターがつながったら送信エンジンを起動し、切れたら止める。
- 映像とのずれ補正: BlueALSA の ClientDelay に「AirPlay 遅延 + バッファ + 補正値」を設定する。
  BlueZ がこれをプロジェクターへ申告し、プロジェクターが映像を遅らせて音と合わせる。
- 音量: Web 画面のスライダー、またはプロジェクターのリモコン(Bluetooth の絶対音量)で AirPlay 音量 0〜100 を変える。
- Web 画面: http://<Pi>:8590/

Web 画面に認証はない。信頼できる宅内 LAN だけで使い、インターネットへ公開しないこと(SECURITY.md)。
"""
import ipaddress
import json
import os
import re
import socket
import subprocess
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

HERE = Path(__file__).resolve().parent
BASE = HERE.parent.parent                     # ~/airplay-bridge
ENGINE = BASE / "bridge" / "engine" / "airplay-bridge-engine"
CONFIG = BASE / "config.json"
RUN = Path(os.environ.get("RUNTIME_DIRECTORY", "/tmp/airplay-bridge"))
CTL = RUN / "ctl"
STATUS = RUN / "status.json"
PORT = int(os.environ.get("AIRPLAY_BRIDGE_PORT", "8590"))
BIND = os.environ.get("AIRPLAY_BRIDGE_BIND", "0.0.0.0")
# 名前で開くときに追加で許可するホスト名(カンマ区切り)
EXTRA_HOSTS = {h.strip().lower() for h in os.environ.get("AIRPLAY_BRIDGE_ALLOWED_HOSTS", "").split(",") if h.strip()}
MAX_BODY = 16 * 1024
PAIRING_SECONDS = 180

DEFAULTS = {
    "target": {"name": "", "host": "", "port": 7000, "et": "0,4"},   # Web 画面で選ぶ
    "volume": 40,
    "latency_ms": 600,        # AirPlay 側の遅延(大きいほど途切れに強い)
    "buffer_ms": 120,         # エンジン内の溜め(時計のずれを吸収する量)
    "sync_offset_ms": 30,     # 映像とのずれの微調整
    "link_remote_volume": True,
}

lock = threading.RLock()
logs = deque(maxlen=300)


def log(msg):
    line = time.strftime("%H:%M:%S ") + msg
    logs.append(line)
    print(line, flush=True)


def load_config():
    cfg = json.loads(json.dumps(DEFAULTS))
    try:
        cfg.update(json.loads(CONFIG.read_text()))
    except FileNotFoundError:
        pass
    except Exception as e:  # noqa: BLE001
        log(f"設定の読み込みに失敗: {e}")
    return cfg


cfg = load_config()


def save_config():
    tmp = CONFIG.with_suffix(".tmp")
    tmp.write_text(json.dumps(cfg, ensure_ascii=False, indent=2))
    tmp.replace(CONFIG)


def run(cmd, timeout=5):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout).stdout
    except Exception:  # noqa: BLE001
        return ""


def sync_ms():
    return cfg["latency_ms"] + cfg["buffer_ms"] + cfg["sync_offset_ms"]


# ---------------------------------------------------------------- Bluetooth / BlueALSA

def find_pcm():
    for line in run(["bluealsactl", "list-pcms"]).split():
        if "/a2dpsnk/source" in line:
            return line
    return None


def pcm_mac(pcm):
    m = re.search(r"dev_([0-9A-F_]{17})", pcm)
    return m.group(1).replace("_", ":") if m else None


def pcm_info(pcm):
    info = {}
    for line in run(["bluealsactl", "info", pcm]).splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            info[k.strip()] = v.strip()
    return info


def device_name(mac):
    for line in run(["bluetoothctl", "info", mac]).splitlines():
        if line.strip().startswith("Name:"):
            return line.split(":", 1)[1].strip()
    return mac


def bt_volume(pcm):
    m = re.search(r"Volume:\s*(\d+)", run(["bluealsactl", "volume", pcm]))
    return int(m.group(1)) if m else None


# ---------------------------------------------------------------- 送信エンジンの管理

class Bridge:
    def __init__(self):
        self.proc = None
        self.pcm = None
        self.mac = None
        self.name = None
        self.last_bt_vol = None
        self.expect_bt_vol = None
        self.restart_requested = False
        self.waiting_target_logged = False

    def start_engine(self):
        RUN.mkdir(parents=True, exist_ok=True)
        t = cfg["target"]
        cmd = [str(ENGINE), "--device", f"bluealsa:DEV={self.mac},PROFILE=a2dp",
               "--host", t["host"], "--port", str(t["port"]), "--et", t.get("et") or "0,4",
               "--latency-ms", str(cfg["latency_ms"]), "--target-ms", str(cfg["buffer_ms"]),
               "--volume", str(cfg["volume"]), "--control", str(CTL), "--status", str(STATUS)]
        log(f"送信開始: {self.name} → {t['name']} ({t['host']})")
        self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        threading.Thread(target=self._pump, args=(self.proc,), daemon=True).start()

    def _pump(self, proc):
        for line in proc.stdout:
            log("engine: " + line.rstrip())

    def stop_engine(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self.proc = None
        try:
            STATUS.unlink()
        except FileNotFoundError:
            pass

    def send(self, line):
        try:
            fd = os.open(CTL, os.O_WRONLY | os.O_NONBLOCK)
            os.write(fd, (line + "\n").encode())
            os.close(fd)
        except OSError:
            pass

    def apply_sync(self):
        if self.pcm:
            run(["bluealsactl", "client-delay", self.pcm, str(sync_ms())])

    def set_volume(self, v, from_remote=False):
        v = max(0, min(100, int(v)))
        with lock:
            cfg["volume"] = v
            save_config()
        self.send(f"volume {v}")
        if self.pcm and not from_remote and cfg["link_remote_volume"]:
            bv = round(v * 127 / 100)
            self.expect_bt_vol = bv
            run(["bluealsactl", "volume", self.pcm, str(bv)])
        return v

    def tick(self):
        pcm = find_pcm()
        if pcm != self.pcm:
            if self.pcm:
                log(f"Bluetooth 切断: {self.name}")
                self.stop_engine()
            self.pcm = pcm
            self.last_bt_vol = None
            if pcm:
                self.mac = pcm_mac(pcm)
                self.name = device_name(self.mac)
                log(f"Bluetooth 接続: {self.name}")
                run(["bluealsactl", "soft-volume", pcm, "off"])   # 音量は Pi で音に掛けず AirPlay 側で扱う
                self.apply_sync()
        if not self.pcm:
            return
        if self.restart_requested:
            self.restart_requested = False
            self.stop_engine()
        if not cfg["target"].get("host"):
            if not self.waiting_target_logged:
                log("送信先が未設定です。Web 画面で選んでください")
                self.waiting_target_logged = True
            return
        self.waiting_target_logged = False
        if self.proc is None or self.proc.poll() is not None:
            if self.proc is not None:
                log("エンジンが終了したので再起動します")
                time.sleep(1)
            self.start_engine()
        # リモコン(Bluetooth の絶対音量 0〜127)→ AirPlay 音量 0〜100
        bv = bt_volume(self.pcm)
        if bv is not None and bv != self.last_bt_vol:
            if self.last_bt_vol is not None and cfg["link_remote_volume"] and bv != self.expect_bt_vol:
                v = self.set_volume(round(bv * 100 / 127), from_remote=True)
                log(f"リモコンで音量変更: {v}")
            self.last_bt_vol = bv
            self.expect_bt_vol = None

    def loop(self):
        count = 0
        while True:
            try:
                with lock:
                    self.tick()
                    if count % 60 == 0:   # 30 秒ごと。bluetoothd が再起動して戻っていても閉じ直す
                        pairing.enforce()
            except Exception as e:  # noqa: BLE001
                log(f"エラー: {e}")
            count += 1
            time.sleep(0.5)

    def status(self):
        st = {}
        try:
            st = json.loads(STATUS.read_text())
        except Exception:  # noqa: BLE001
            pass
        info = pcm_info(self.pcm) if self.pcm else {}
        return {
            "bt": {"connected": bool(self.pcm), "name": self.name if self.pcm else None,
                   "codec": info.get("Selected codec"), "playing": info.get("Running") == "true"},
            "engine": st,
            "config": cfg,
            "sync_ms": sync_ms(),
            "logs": list(logs)[-80:],
        }


bridge = Bridge()


# ---------------------------------------------------------------- Bluetooth のペアリング受付
# bt-agent は確認なし(NoInputNoOutput)で応答するので、受付を開けたままだと近くの誰でも
# ペアリングできてしまう。ふだんは閉じ、Web 画面で押したときだけ開く。

class Pairing:
    def __init__(self):
        self.until = 0.0

    def is_open(self):
        return time.monotonic() < self.until

    def open(self):
        self.until = time.monotonic() + PAIRING_SECONDS
        run(["bluetoothctl", "pairable", "on"])
        run(["bluetoothctl", "discoverable-timeout", str(PAIRING_SECONDS)])
        run(["bluetoothctl", "discoverable", "on"])

    def enforce(self):
        if not self.is_open():
            run(["bluetoothctl", "discoverable", "off"])
            run(["bluetoothctl", "pairable", "off"])


pairing = Pairing()


# ---------------------------------------------------------------- AirPlay 機器の検出

def decode_avahi(s):
    raw = re.sub(rb"\\(\d{3})", lambda m: bytes([int(m.group(1))]), s.encode("latin-1", "backslashreplace"))
    return raw.decode("utf-8", "replace")


discover_cache = (0.0, [])
discover_lock = threading.Lock()


def discover():
    global discover_cache
    with discover_lock:      # avahi-browse を同時にいくつも起動させない
        if time.monotonic() - discover_cache[0] > 5:
            discover_cache = (time.monotonic(), browse_raop())
        return discover_cache[1]


def browse_raop():
    found = {}
    for line in run(["avahi-browse", "-rpt", "_raop._tcp"], timeout=8).splitlines():
        p = line.split(";")
        if len(p) < 10 or p[0] != "=" or p[2] != "IPv4":
            continue
        name = decode_avahi(p[3]).split("@", 1)[-1]
        ip, port = p[7], int(p[8])
        et = ""
        m = re.search(r'"et=([^"]*)"', line)
        if m:
            et = m.group(1)
        prev = found.get(name)
        if prev is None or prev["host"].startswith("169.254.") and not ip.startswith("169.254."):
            found[name] = {"name": name, "host": ip, "port": port, "et": et or "0,4"}
    return sorted(found.values(), key=lambda d: d["name"])


# ---------------------------------------------------------------- Web

def parse_ip(host):
    try:
        return ipaddress.ip_address(host)
    except ValueError:
        return None


def host_allowed(header):
    """DNS リバインディング対策: Host が IP か、この Pi 自身の名前のときだけ受け付ける。"""
    host = urlsplit("//" + (header or "")).hostname
    if not host:
        return False
    host = host.lower()
    if parse_ip(host):
        return True
    me = socket.gethostname().lower()
    return (host in ("localhost", me) or host in EXTRA_HOSTS
            or re.fullmatch(re.escape(me) + r"(-\d+)?\.local", host) is not None
            or re.fullmatch(re.escape(me) + r"\.[a-z0-9-]+\.ts\.net", host) is not None)


def clean_target(data):
    """送信先の検証。音声を外部へ送らせないよう、宅内のアドレスだけ受け付ける。"""
    name = data["name"]
    if not isinstance(name, str) or not 0 < len(name) <= 128 or not name.isprintable():
        raise ValueError("name")
    ip = parse_ip(data["host"]) if isinstance(data["host"], str) else None
    if ip is None or not ip.is_private or ip.is_loopback or ip.is_unspecified or ip.is_multicast:
        raise ValueError("host")
    port = int(data["port"])
    if not 0 < port < 65536:
        raise ValueError("port")
    et = data["et"]
    if not isinstance(et, str) or not re.fullmatch(r"\d{1,2}(,\d{1,2}){0,15}", et):
        raise ValueError("et")
    return {"name": name, "host": str(ip), "port": port, "et": et}


SECURITY_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "Cache-Control": "no-store",
    "Content-Security-Policy": "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                               "connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; "
                               "frame-ancestors 'none'",
}


class Handler(BaseHTTPRequestHandler):
    server_version = "AirPlayBridge"
    sys_version = ""
    timeout = 10

    def log_message(self, *a):
        pass

    def end_headers(self):
        for k, v in SECURITY_HEADERS.items():
            self.send_header(k, v)
        super().end_headers()

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _trusted_request(self):
        """ほかのサイトを開いたブラウザ経由の操作(CSRF / DNS リバインディング)を拒否する。"""
        if not host_allowed(self.headers.get("Host")):
            return False
        if self.command != "POST":
            return True
        origin = self.headers.get("Origin")
        if origin and urlsplit(origin).netloc.lower() != (self.headers.get("Host") or "").lower():
            return False
        # application/json は他サイトからだと事前確認が必要になり、それに応答しないので送れない
        return (self.headers.get("Content-Type") or "").split(";")[0].strip().lower() == "application/json"

    def do_GET(self):
        if not self._trusted_request():
            return self._json({"error": "forbidden"}, 403)
        if self.path in ("/", "/index.html"):
            body = (HERE / "index.html").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/api/status":
            with lock:
                self._json(bridge.status())
        elif self.path == "/api/devices":
            self._json(discover())
        else:
            self._json({"error": "not found"}, 404)

    def do_POST(self):
        if not self._trusted_request():
            return self._json({"error": "forbidden"}, 403)
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return self._json({"error": "bad length"}, 400)
        if not 0 <= n <= MAX_BODY:
            return self._json({"error": "too large"}, 413)
        try:
            data = json.loads(self.rfile.read(n) or b"{}")
            if not isinstance(data, dict):
                raise ValueError("object required")
            return self._route(data)
        except (ValueError, KeyError, TypeError, OverflowError):
            return self._json({"error": "bad request"}, 400)

    def _route(self, data):
        with lock:
            if self.path == "/api/volume":
                return self._json({"volume": bridge.set_volume(data["volume"])})
            if self.path == "/api/sync":
                cfg["sync_offset_ms"] = max(-500, min(1000, int(data["offset_ms"])))
                save_config()
                bridge.apply_sync()
                log(f"ずれ補正: {cfg['sync_offset_ms']:+d}ms(申告 {sync_ms()}ms)")
                return self._json({"sync_ms": sync_ms()})
            if self.path == "/api/latency":
                cfg["latency_ms"] = max(300, min(2000, int(data["latency_ms"])))
                save_config()
                bridge.apply_sync()
                bridge.restart_requested = True
                log(f"AirPlay 遅延を {cfg['latency_ms']}ms に変更(再接続します)")
                return self._json({"ok": True})
            if self.path == "/api/target":
                cfg["target"] = clean_target(data)
                save_config()
                bridge.restart_requested = True
                log(f"送信先を {cfg['target']['name']} に変更")
                return self._json({"ok": True})
            if self.path == "/api/link":
                cfg["link_remote_volume"] = bool(data["enabled"])
                save_config()
                return self._json({"ok": True})
            if self.path == "/api/pairing":
                pairing.open()
                log(f"ペアリング受付を開始({PAIRING_SECONDS // 60}分間)")
                return self._json({"ok": True})
            if self.path == "/api/restart":
                bridge.restart_requested = True
                log("再接続を要求")
                return self._json({"ok": True})
        return self._json({"error": "not found"}, 404)


def main():
    RUN.mkdir(parents=True, exist_ok=True)
    save_config()
    log(f"AirPlay Bridge 起動(Web: http://{BIND}:{PORT}/)")
    threading.Thread(target=bridge.loop, daemon=True).start()
    ThreadingHTTPServer((BIND, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
