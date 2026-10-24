"""Receive a generated PNG asset on stdin, validate its hash and store inside img/."""
import argparse, base64, hashlib, json, os
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("filename")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
target = (root / "img" / args.filename).resolve()
if target.parent != (root / "img").resolve() or target.suffix.lower() != ".png":
    raise SystemExit("Only direct PNG filenames inside img are accepted")
payload = json.load(__import__("sys").stdin)
data = base64.b64decode(payload["data"], validate=True)
if not data.startswith(b"\x89PNG\r\n\x1a\n"):
    raise SystemExit("Input is not a PNG")
digest = hashlib.sha256(data).hexdigest()
if digest != payload["sha256"]:
    raise SystemExit("SHA-256 mismatch")
target.parent.mkdir(exist_ok=True)
tmp = target.with_suffix(".png.partial")
tmp.write_bytes(data)
os.replace(tmp, target)
print(json.dumps({"path": str(target), "bytes":len(data), "sha256":digest}, ensure_ascii=False))

