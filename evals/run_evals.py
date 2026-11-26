#!/usr/bin/env python3
"""
mini Claude Code 评测框架（M0）。

每道题 = 一个样本项目（fixture）+ 一条需求（prompt）+ 一条验证命令（verify）。
流程：复制 fixture 到临时目录 → 用 Headless 模式跑 mcc → 在同一目录跑验证命令 → 记录 Pass/Fail 与指标。

为什么要每题重复跑多次（--repeat）：模型输出有随机性，只跑一次的成功率差异很可能是噪声。
报告同时给出 pass@1（平均通过率）和 pass@k（k 次里至少过一次）。

隔离：每次运行使用独立临时 --state-dir，并加 --bare（不加载全局 Skills、不做记忆提取），
保证本机 ~/.mcc 里的记忆与技能不会污染结果。

用法：
  python3 evals/run_evals.py                       # 跑全部任务，每题 3 次
  python3 evals/run_evals.py --task fix-off-by-one --repeat 1
  python3 evals/run_evals.py --label baseline      # 报告写到 evals/reports/<时间>-baseline.{json,md}
"""
import argparse
import json
import os
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
DEFAULT_MCC = REPO / "build" / "install" / "mcc" / "bin" / "mcc"


def load_tasks(only):
    tasks = []
    for f in sorted((ROOT / "tasks").glob("*.json")):
        t = json.loads(f.read_text(encoding="utf-8"))
        t.setdefault("id", f.stem)
        if only and t["id"] not in only:
            continue
        tasks.append(t)
    return tasks


def run_once(task, mcc, model, timeout_default):
    work = Path(tempfile.mkdtemp(prefix=f"mcc-eval-{task['id']}-"))
    state = Path(tempfile.mkdtemp(prefix="mcc-eval-state-"))
    try:
        project = work / "project"
        shutil.copytree(ROOT / "fixtures" / task["fixture"], project)
        cmd = [str(mcc), "-p", task["prompt"], "--dir", str(project), "--output", "json",
               "--permission", task.get("permission", "auto"), "--state-dir", str(state),
               "--bare", "-q", "--max-steps", str(task.get("max_steps", 30))]
        if model:
            cmd += ["--model", model]
        timeout = task.get("timeout_s", timeout_default)
        started = time.time()
        report, error = {}, None
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
            last = (proc.stdout.strip().splitlines() or [""])[-1]
            try:
                report = json.loads(last)
            except json.JSONDecodeError:
                error = f"无法解析 mcc 输出（exit={proc.returncode}）：{proc.stderr[-500:]}"
        except subprocess.TimeoutExpired:
            error = f"mcc 超时（{timeout}s）"
        wall = time.time() - started

        verify = subprocess.run(task["verify"], shell=True, cwd=project, capture_output=True, text=True,
                                timeout=task.get("verify_timeout_s", 300))
        usage = report.get("usage", {})
        return {
            "task": task["id"],
            "passed": verify.returncode == 0 and error is None,
            "agent_status": report.get("status", "harness_error"),
            "error": error,
            "verify_output": (verify.stdout + verify.stderr)[-800:] if verify.returncode else "",
            "steps": report.get("steps", 0),
            "tool_calls": report.get("tool_calls", 0),
            "tool_errors": report.get("tool_errors", 0),
            "prompt_tokens": usage.get("prompt_tokens", 0),
            "completion_tokens": usage.get("completion_tokens", 0),
            "duration_s": round(wall, 1),
        }
    finally:
        shutil.rmtree(work, ignore_errors=True)
        shutil.rmtree(state, ignore_errors=True)


def summarize(runs, tasks):
    def mean(key, rs):
        return round(statistics.mean(r[key] for r in rs), 1) if rs else 0

    per_task = []
    for t in tasks:
        rs = [r for r in runs if r["task"] == t["id"]]
        per_task.append({
            "task": t["id"], "category": t.get("category", ""), "runs": len(rs),
            "pass_rate": round(sum(r["passed"] for r in rs) / len(rs), 2) if rs else 0,
            "pass_at_k": any(r["passed"] for r in rs),
            "avg_steps": mean("steps", rs), "avg_prompt_tokens": mean("prompt_tokens", rs),
            "avg_completion_tokens": mean("completion_tokens", rs), "avg_duration_s": mean("duration_s", rs),
        })
    total = len(runs)
    rates = [p["pass_rate"] for p in per_task]
    return {
        "runs": total,
        "pass_at_1": round(sum(r["passed"] for r in runs) / total, 3) if total else 0,
        "pass_at_1_stdev_across_tasks": round(statistics.pstdev(rates), 3) if rates else 0,
        "pass_at_k": round(sum(p["pass_at_k"] for p in per_task) / len(per_task), 3) if per_task else 0,
        "avg_steps": mean("steps", runs),
        "avg_prompt_tokens": mean("prompt_tokens", runs),
        "avg_completion_tokens": mean("completion_tokens", runs),
        "tool_error_rate": round(sum(r["tool_errors"] for r in runs) / max(1, sum(r["tool_calls"] for r in runs)), 3),
        "avg_duration_s": mean("duration_s", runs),
        "per_task": per_task,
    }


def to_markdown(label, meta, s):
    lines = [f"# Eval 报告：{label}", "",
             f"- 时间：{meta['time']}  模型：{meta['model'] or '(配置默认)'}  每题重复：{meta['repeat']}",
             f"- 总运行：{s['runs']}  **pass@1：{s['pass_at_1']:.1%}**  pass@{meta['repeat']}：{s['pass_at_k']:.1%}",
             f"- 平均步数：{s['avg_steps']}  平均 prompt/completion Token：{s['avg_prompt_tokens']} / "
             f"{s['avg_completion_tokens']}  工具错误率：{s['tool_error_rate']:.1%}  平均耗时：{s['avg_duration_s']}s",
             "", "| 任务 | 类别 | 通过率 | 平均步数 | 平均 prompt Token | 平均耗时(s) |", "|---|---|---|---|---|---|"]
    for p in s["per_task"]:
        lines.append(f"| {p['task']} | {p['category']} | {p['pass_rate']:.0%} | {p['avg_steps']} | "
                     f"{p['avg_prompt_tokens']} | {p['avg_duration_s']} |")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--task", action="append", help="只跑指定任务（可重复）")
    ap.add_argument("--repeat", type=int, default=3)
    ap.add_argument("--model")
    ap.add_argument("--label", default="run")
    ap.add_argument("--mcc", default=str(DEFAULT_MCC))
    ap.add_argument("--timeout", type=int, default=600, help="单次运行默认超时（秒）")
    args = ap.parse_args()

    if not Path(args.mcc).exists():
        sys.exit(f"找不到 {args.mcc}，请先执行 ./gradlew installDist")
    if not os.environ.get("OPENAI_API_KEY"):
        sys.exit("请先设置 OPENAI_API_KEY（评测会调用真实模型并产生费用）")
    tasks = load_tasks(args.task)
    if not tasks:
        sys.exit("没有匹配的任务")

    runs = []
    for t in tasks:
        for i in range(args.repeat):
            r = run_once(t, args.mcc, args.model, args.timeout)
            runs.append(r)
            mark = "PASS" if r["passed"] else "FAIL"
            print(f"[{mark}] {t['id']} #{i + 1}  steps={r['steps']} tokens={r['prompt_tokens']}+"
                  f"{r['completion_tokens']} {r['duration_s']}s {r['error'] or ''}", flush=True)

    meta = {"time": time.strftime("%Y-%m-%d %H:%M:%S"), "model": args.model, "repeat": args.repeat}
    summary = summarize(runs, tasks)
    out = ROOT / "reports"
    out.mkdir(exist_ok=True)
    stem = time.strftime("%Y%m%d-%H%M%S") + "-" + args.label
    (out / f"{stem}.json").write_text(json.dumps({"meta": meta, "summary": summary, "runs": runs},
                                                 ensure_ascii=False, indent=2), encoding="utf-8")
    md = to_markdown(args.label, meta, summary)
    (out / f"{stem}.md").write_text(md, encoding="utf-8")
    print("\n" + md)
    print(f"报告：{out / stem}.{{json,md}}")


if __name__ == "__main__":
    main()
