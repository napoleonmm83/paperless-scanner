#!/usr/bin/env python3
"""
Aggregate all wave*.json files into the master REVIEW report and a refactoring roadmap.

Output:
- docs/code-reviews/REVIEW_2026-05-04.md (severity matrix + full index)
- docs/code-reviews/ROADMAP_2026-05.md   (ordered refactoring plan)
"""
from __future__ import annotations
import glob
import json
import pathlib
import sys
from collections import defaultdict

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

ROOT = pathlib.Path(__file__).resolve().parents[2]
DATA_DIR = ROOT / "docs" / "code-reviews" / "data"
REVIEW_PATH = ROOT / "docs" / "code-reviews" / "REVIEW_2026-05-04.md"
ROADMAP_PATH = ROOT / "docs" / "code-reviews" / "ROADMAP_2026-05.md"

SEVERITY_ORDER = ["critical", "high", "medium", "low"]
SEVERITY_EMOJI = {"critical": "🔴", "high": "🟠", "medium": "🟡", "low": "🔵"}
EFFORT_HOURS = {"XS": 0.5, "S": 2, "M": 5, "L": 12, "XL": 30}


def load_all() -> list[dict]:
    findings = []
    for fn in sorted(glob.glob(str(DATA_DIR / "wave*.json"))):
        with open(fn, "r", encoding="utf-8") as fh:
            d = json.load(fh)
        findings.extend(d["findings"])
    return findings


def fmt_link(f: dict) -> str:
    issue = f.get("issue_url") or "_pending_"
    md = f.get("markdown_path", "")
    md_link = f"[`{f['id']}`](./{pathlib.Path(md).name})" if md else f["id"]
    issue_link = f"[#{issue.split('/')[-1]}]({issue})" if issue.startswith("http") else issue
    return f"{md_link} · {issue_link}"


def build_severity_matrix(findings: list[dict]) -> str:
    counts = defaultdict(list)
    for f in findings:
        counts[f["severity"]].append(f)
    rows = []
    rows.append("| Severity | Count | Issues |")
    rows.append("|----------|-------|--------|")
    for sev in SEVERITY_ORDER:
        bucket = counts.get(sev, [])
        ids = ", ".join(f"`{x['id']}`" for x in bucket[:8])
        if len(bucket) > 8:
            ids += f", … (+{len(bucket) - 8} more)"
        rows.append(f"| {SEVERITY_EMOJI[sev]} {sev.title()} | {len(bucket)} | {ids or '—'} |")
    return "\n".join(rows)


def build_findings_index(findings: list[dict]) -> str:
    rows = ["| ID | Severity | Area | Title | Effort | Issue |", "|----|----------|------|-------|--------|-------|"]
    sev_rank = {s: i for i, s in enumerate(SEVERITY_ORDER)}
    findings_sorted = sorted(findings, key=lambda f: (sev_rank.get(f["severity"], 99), f["id"]))
    for f in findings_sorted:
        title = f["title"].replace("|", "\\|")
        if len(title) > 90:
            title = title[:87] + "..."
        issue = f.get("issue_url", "")
        issue_link = f"[#{issue.split('/')[-1]}]({issue})" if issue else "_pending_"
        md = f.get("markdown_path", "")
        md_link = f"[{f['id']}](./{pathlib.Path(md).name})" if md else f["id"]
        rows.append(
            f"| {md_link} | {SEVERITY_EMOJI[f['severity']]} {f['severity']} | `{f['area']}/{f.get('subarea','')}` | {title} | `{f['effort']}` | {issue_link} |"
        )
    return "\n".join(rows)


def build_area_breakdown(findings: list[dict]) -> str:
    by_area = defaultdict(list)
    for f in findings:
        by_area[f["area"]].append(f)
    rows = ["| Area | Count | Critical | High | Medium | Low |", "|------|-------|---------:|-----:|-------:|----:|"]
    for area, items in sorted(by_area.items(), key=lambda x: -len(x[1])):
        c = sum(1 for x in items if x["severity"] == "critical")
        h = sum(1 for x in items if x["severity"] == "high")
        m = sum(1 for x in items if x["severity"] == "medium")
        l = sum(1 for x in items if x["severity"] == "low")
        rows.append(f"| `{area}` | {len(items)} | {c} | {h} | {m} | {l} |")
    return "\n".join(rows)


def estimate_effort(findings: list[dict]) -> tuple[float, dict]:
    total = 0.0
    bucket = defaultdict(float)
    for f in findings:
        h = EFFORT_HOURS.get(f["effort"], 0)
        total += h
        bucket[f["effort"]] += h
    return total, bucket


def build_roadmap(findings: list[dict]) -> str:
    sev_rank = {s: i for i, s in enumerate(SEVERITY_ORDER)}
    sorted_f = sorted(findings, key=lambda f: (sev_rank.get(f["severity"], 99), f["id"]))

    lines = [
        "# Refactoring Roadmap — Code Review 2026-05",
        "",
        "> Ordered execution plan derived from `REVIEW_2026-05-04.md`.",
        "> Sequence: Critical → High → Medium → Low, grouped by area for sprint cohesion.",
        "",
    ]

    total, bucket = estimate_effort(findings)
    lines += [
        "## Effort Estimate",
        "",
        "| Bucket | Hours | Days (8h) |",
        "|--------|------:|----------:|",
    ]
    for sz in ["XS", "S", "M", "L", "XL"]:
        h = bucket.get(sz, 0)
        lines.append(f"| `{sz}` | {h:.1f} | {h/8:.1f} |")
    lines.append(f"| **Total** | **{total:.1f}** | **{total/8:.1f}** |")
    lines.append("")
    lines.append("Effort assumes single dev; parallelizable across topics.")
    lines.append("")

    # Sprint plan
    lines += [
        "## Sprint Plan",
        "",
        "### 🚨 Sprint 1 — Critical Security & Correctness (~1–2 weeks)",
        "",
        "Focus: AppLock dual-state, layering, cleartext HTTP, auth NPE.",
        "",
    ]
    for f in sorted_f:
        if f["severity"] == "critical":
            issue = f.get("issue_url", "")
            link = f"[#{issue.split('/')[-1]}]({issue})" if issue else "_pending_"
            lines.append(f"- {SEVERITY_EMOJI[f['severity']]} **{f['id']}** {link} · `{f['effort']}` — {f['title']}")
    lines.append("")

    lines += [
        "### 🟠 Sprint 2–3 — High-Severity Refactors (~3–4 weeks)",
        "",
        "Focus: God-classes (DocumentRepository, HomeViewModel, large screens), reactive Flow migration, Worker resilience.",
        "",
    ]
    high = [f for f in sorted_f if f["severity"] == "high"]
    high_by_area = defaultdict(list)
    for f in high:
        high_by_area[f["area"]].append(f)
    for area, items in sorted(high_by_area.items()):
        lines.append(f"#### `{area}` ({len(items)})")
        lines.append("")
        for f in items:
            issue = f.get("issue_url", "")
            link = f"[#{issue.split('/')[-1]}]({issue})" if issue else "_pending_"
            lines.append(f"- **{f['id']}** {link} · `{f['effort']}` — {f['title']}")
        lines.append("")

    lines += [
        "### 🟡 Sprint 4+ — Medium-Severity Hygiene (rolling)",
        "",
        f"{sum(1 for f in sorted_f if f['severity'] == 'medium')} findings — group by area / pair with feature work.",
        "",
        "See full index in [`REVIEW_2026-05-04.md`](./REVIEW_2026-05-04.md#findings-index).",
        "",
        "### 🔵 Backlog — Low (XS / nice-to-have)",
        "",
        f"{sum(1 for f in sorted_f if f['severity'] == 'low')} findings — bundle into housekeeping PRs.",
        "",
    ]

    # Quick wins (XS + high-severity)
    quick_wins = [
        f for f in sorted_f if f["effort"] in {"XS", "S"} and f["severity"] in {"critical", "high"}
    ]
    if quick_wins:
        lines += [
            "## ⚡ Quick Wins (high-severity, ≤ 3h effort)",
            "",
            "Tackle these first — high impact, low effort:",
            "",
        ]
        for f in quick_wins:
            issue = f.get("issue_url", "")
            link = f"[#{issue.split('/')[-1]}]({issue})" if issue else "_pending_"
            lines.append(f"- {SEVERITY_EMOJI[f['severity']]} **{f['id']}** {link} · `{f['effort']}` — {f['title']}")
        lines.append("")

    # Long-term refactors
    big = [f for f in sorted_f if f["effort"] in {"L", "XL"}]
    lines += [
        "## 🏗️ Long-Term Refactors (L/XL effort)",
        "",
        "These need dedicated planning + design review:",
        "",
    ]
    for f in big:
        issue = f.get("issue_url", "")
        link = f"[#{issue.split('/')[-1]}]({issue})" if issue else "_pending_"
        lines.append(f"- {SEVERITY_EMOJI[f['severity']]} **{f['id']}** {link} · `{f['effort']}` — {f['title']}")
    lines.append("")

    return "\n".join(lines)


def main():
    findings = load_all()
    print(f"Loaded {len(findings)} findings")

    # Master report
    review = REVIEW_PATH.read_text(encoding="utf-8")
    matrix = build_severity_matrix(findings)
    index = build_findings_index(findings)
    area_table = build_area_breakdown(findings)
    total_effort, _ = estimate_effort(findings)

    new_summary = f"""## 🚨 Severity Matrix

{matrix}

**Total findings: {len(findings)}** · estimated effort: **~{total_effort:.0f} hours** (~{total_effort/8:.0f} dev-days, single developer)

## 📊 Findings by Area

{area_table}
"""

    # Replace the placeholder severity matrix
    start_marker = "## 🚨 Severity Matrix"
    end_marker = "## 🗺️ Module Map"
    if start_marker in review and end_marker in review:
        before = review.split(start_marker)[0]
        after = review.split(end_marker, 1)[1]
        review = f"{before}{new_summary}\n---\n\n{end_marker}{after}"

    # Append findings index at the placeholder
    placeholder = "_(empty — Wave 1 in progress)_"
    if placeholder in review:
        review = review.replace(placeholder, index)
    else:
        # Replace existing index between "## 📑 Findings Index" and "## 📚 Output Format"
        idx_start = "## 📑 Findings Index"
        idx_end = "## 📚 Output Format"
        if idx_start in review and idx_end in review:
            before = review.split(idx_start)[0]
            after = review.split(idx_end, 1)[1]
            review = f"{before}{idx_start}\n\n> Findings sorted by severity then ID. Click ID for atomic markdown, # for GitHub issue.\n\n{index}\n\n---\n\n{idx_end}{after}"

    # Update status
    review = review.replace(
        "> **Status:** 🟡 In Progress",
        "> **Status:** ✅ Complete (3 waves, 120 findings, 120 GitHub issues)"
    )

    REVIEW_PATH.write_text(review, encoding="utf-8")
    print(f"Updated {REVIEW_PATH.relative_to(ROOT)}")

    # Roadmap
    ROADMAP_PATH.write_text(build_roadmap(findings), encoding="utf-8")
    print(f"Wrote {ROADMAP_PATH.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
