#!/usr/bin/env python3
"""Tiny UI automation over adb + uiautomator, used by demo/run-demo.sh to record the math
challenge. Screenshots are taken host-side through the emulator console."""
import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

OUT = os.environ.get("OUT", "demo-output")


def adb(*args, check=True):
    r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=300)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr}")
    return r.stdout


def dump():
    for _ in range(10):
        out = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
        m = re.search(r"(<\?xml.*</hierarchy>)", out, re.S)
        if m:
            return ET.fromstring(m.group(1))
        time.sleep(1)
    raise RuntimeError("uiautomator dump failed")


def find(root, rid=None, text=None):
    for n in root.iter("node"):
        if rid and not n.get("resource-id", "").endswith(":id/" + rid):
            continue
        if text is not None and n.get("text") != text:
            continue
        yield n


def bounds(n):
    return list(map(int, re.findall(r"\d+", n.get("bounds"))))


def center(n):
    x1, y1, x2, y2 = bounds(n)
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(n):
    x, y = center(n)
    adb("shell", "input", "tap", str(x), str(y))


def dismiss_system_hints(root):
    """Close one-time system hints (e.g. "Viewing full screen") that cover the app."""
    for label in ("Got it", "GOT IT"):
        for n in find(root, text=label):
            tap(n)
            time.sleep(1)
            return True
    return False


def wait_for(rid=None, text=None, timeout=120):
    end = time.time() + timeout
    while time.time() < end:
        root = dump()
        if text not in ("Got it", "GOT IT") and dismiss_system_hints(root):
            root = dump()
        found = list(find(root, rid=rid, text=text))
        if found:
            return root, found[0]
        time.sleep(1)
    raise TimeoutError(f"{rid or text!r} not shown within {timeout}s")


def shot(name):
    tmp = tempfile.mkdtemp()
    adb("emu", "screenrecord", "screenshot", tmp)
    files = []
    for _ in range(30):
        files = glob.glob(os.path.join(tmp, "*.png"))
        if files:
            break
        time.sleep(0.5)
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name + ".png")
    shutil.move(files[0], path)
    shutil.rmtree(tmp, ignore_errors=True)
    print("screenshot", path, flush=True)


def evaluate(problem):
    expr = problem.replace("= ?", "").replace("×", "*").replace("−", "-").strip()
    if not re.fullmatch(r"[0-9+\-*() ]+", expr):
        raise ValueError(f"unexpected problem text: {problem!r}")
    return eval(expr)  # only digits, + - * and parentheses


def type_answer(root, value):
    keys = {n.get("text"): n for n in root.iter("node")
            if re.fullmatch(r"\d", n.get("text", "")) and n.get("clickable") == "true"}
    for digit in str(value):
        tap(keys[digit])
        time.sleep(0.4)


def press_ok():
    _, ok = wait_for(text="OK")
    tap(ok)


def swipe_dismiss():
    """Slide the alarm button to the dismiss side (right in LTR), like a user would."""
    root, button = wait_for(rid="alarm_button", timeout=60)
    zone = next(find(root, rid="slide_zone_layout"))
    bx, by = center(button)
    zx1, _, zx2, _ = bounds(zone)
    adb("shell", "input", "swipe", str(bx), str(by), str(zx2 - 10), str(by), "900")


def solve_all(prefix):
    """Solve every problem of the challenge; the first answer is deliberately wrong once."""
    step = 1
    wrong_done = False
    while True:
        try:
            root, problem = wait_for(rid="math_challenge_text", timeout=15)
        except TimeoutError:
            return
        text = problem.get("text")
        answer = evaluate(text)
        print(f"problem {step}: {text} -> {answer}", flush=True)
        time.sleep(1)
        shot(f"{prefix}{step}a-problem")

        if not wrong_done:
            type_answer(root, answer + 7)
            time.sleep(0.8)
            press_ok()
            time.sleep(1.5)
            shot(f"{prefix}{step}b-wrong-answer")
            wrong_done = True
            root = dump()

        type_answer(root, answer)
        time.sleep(0.8)
        shot(f"{prefix}{step}c-answer-typed")
        press_ok()
        time.sleep(2)
        step += 1
        if step > 10:
            raise RuntimeError("too many problems")


if __name__ == "__main__":
    cmd, *args = sys.argv[1:]
    if cmd == "shot":
        shot(args[0])
    elif cmd == "swipe-dismiss":
        swipe_dismiss()
    elif cmd == "solve-all":
        solve_all(args[0])
    elif cmd == "wait-id":
        wait_for(rid=args[0], timeout=int(args[1]) if len(args) > 1 else 120)
    elif cmd == "texts":
        for n in dump().iter("node"):
            if n.get("text") or n.get("content-desc"):
                print(repr(n.get("text")), repr(n.get("content-desc")), n.get("resource-id"), n.get("bounds"))
    else:
        sys.exit(f"unknown command {cmd}")
