#!/usr/bin/env python3
"""把构建失败的细节提成 GitHub annotation。

存在的理由是权限：完整的构建日志需要仓库管理员权限才能通过 API 拉取，而
annotation 对任何能看到这个仓库的人都是公开可读的。没有这一步的话，一次
失败在外部看来就只有一句「Process completed with exit code 1」，排查只能靠
猜——而猜的代价是每错一次就多等一整轮 CI。

同时覆盖两类失败，它们的证据在不同地方：
  * 测试断言失败 —— 在 JUnit XML 里
  * 编译错误 —— 不产生 XML，只能从构建日志里捞 `e: ` 开头的行
"""

from __future__ import annotations

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

# annotation 的正文有长度上限，过长会被整条丢掉，宁可截断也不要一条都不剩
MAX_MESSAGE = 500
MAX_ITEMS = 30


def _clean(text: str) -> str:
    return " ".join(text.split())[:MAX_MESSAGE]


def _emit(level: str, title: str, message: str) -> None:
    # annotation 是按行解析的，正文里的换行会截断整条消息
    print(f"::{level} title={title}::{_clean(message)}")


def report_failed_tests() -> int:
    count = 0
    for path in glob.glob("**/build/test-results/**/TEST-*.xml", recursive=True):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            for bad in list(case.findall("failure")) + list(case.findall("error")):
                if count >= MAX_ITEMS:
                    return count
                name = f'{case.get("classname", "?")}.{case.get("name", "?")}'
                detail = bad.get("message") or (bad.text or "")
                _emit("error", f"测试失败 {name}", detail)
                count += 1
    return count


def report_compile_errors(log_path: str) -> int:
    if not log_path or not os.path.exists(log_path):
        return 0
    # Kotlin 编译错误统一是 `e: file:///... : 描述`
    pattern = re.compile(r"^e: (.+)$")
    seen: set[str] = set()
    count = 0
    with open(log_path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            match = pattern.match(line.strip())
            if not match:
                continue
            detail = match.group(1)
            if detail in seen:
                continue
            seen.add(detail)
            if count >= MAX_ITEMS:
                break
            _emit("error", "编译错误", detail)
            count += 1
    return count


def main() -> int:
    log_path = sys.argv[1] if len(sys.argv) > 1 else ""
    total = report_failed_tests() + report_compile_errors(log_path)
    if total == 0:
        _emit(
            "error",
            "构建失败但没有可提取的细节",
            "既没有失败的测试用例，也没有 Kotlin 编译错误。"
            "可能挂在 Gradle 配置阶段、依赖解析或 R8，需要查看完整日志。",
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
