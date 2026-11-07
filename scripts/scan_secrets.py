#!/usr/bin/env python3
"""
Fails if tracked files contain likely secrets: private keys, keystores, cloud credentials,
signing passwords, or long high-entropy tokens assigned to secret-like names.
Usage: python3 scripts/scan_secrets.py   (scans `git ls-files`)
"""
import math, os, re, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATTERNS = {
    "private key": re.compile(r"-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    "AWS access key": re.compile(r"AKIA[0-9A-Z]{16}"),
    "Google API key": re.compile(r"AIza[0-9A-Za-z_\-]{35}"),
    "GitHub token": re.compile(r"gh[pousr]_[A-Za-z0-9]{36,}"),
    "Slack token": re.compile(r"xox[baprs]-[A-Za-z0-9-]{10,}"),
    "hard-coded signing password": re.compile(r"(storePassword|keyPassword)\s*=\s*\"[^\"$]+\""),
}
ASSIGN = re.compile(r"(api[_-]?key|secret|token|password)\s*[:=]\s*[\"']([A-Za-z0-9+/_\-]{24,})[\"']", re.I)
BINARY_SECRET_EXT = (".jks", ".keystore", ".p12", ".pem", ".key")

def entropy(s):
    return -sum(s.count(c) / len(s) * math.log2(s.count(c) / len(s)) for c in set(s))

def main():
    files = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.split()
    findings = []
    for f in files:
        if f.endswith(BINARY_SECRET_EXT):
            findings.append(f"{f}: key/keystore file is tracked")
            continue
        if f.endswith((".png", ".jar", ".webp", ".zip")) or f == "scripts/scan_secrets.py":
            continue
        try:
            text = open(os.path.join(ROOT, f), encoding="utf-8").read()
        except (UnicodeDecodeError, FileNotFoundError):
            continue
        for name, rx in PATTERNS.items():
            for m in rx.finditer(text):
                findings.append(f"{f}:{text[:m.start()].count(chr(10)) + 1}: {name}")
        for m in ASSIGN.finditer(text):
            if entropy(m.group(2)) > 3.5:
                findings.append(f"{f}:{text[:m.start()].count(chr(10)) + 1}: high-entropy value assigned to '{m.group(1)}'")
    print(f"Scanned {len(files)} tracked files: {len(findings)} finding(s)")
    for x in findings:
        print("  " + x)
    sys.exit(1 if findings else 0)

if __name__ == "__main__":
    main()
