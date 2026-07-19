#!/usr/bin/env python3
"""
mermaid_renderer.py
Phase 4：将 Mermaid 源码渲染为 PNG，供 Premium PPT 思维导图页嵌入。

设计原则：
  - 渲染失败 / 超时 / 环境缺失 → 返回 False，绝不向上抛异常
  - 子进程一次性调用，无重试循环
  - 临时 .mmd 文件在 finally 中必定清理
"""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

logger = logging.getLogger(__name__)

# mmdc 可执行文件名（npm global 安装后通常为 mmdc / mmdc.cmd）
_MMDC_EXECUTABLE = "mmdc"

# Windows 常见浏览器路径（PUPPETEER_SKIP_DOWNLOAD 时需指定）
_BROWSER_CANDIDATES = (
    Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe"),
    Path(r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"),
    Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"),
    Path(r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"),
)


def _resolve_mmdc_executable() -> str | None:
    """
    解析 mmdc 完整路径。
    Windows 上 npm 全局命令多为 mmdc.cmd，必须传绝对路径给 Popen，不能只用 'mmdc'。
    """
    return shutil.which(_MMDC_EXECUTABLE)


def _is_mmdc_available() -> bool:
    """检测系统 PATH 中是否存在 @mermaid-js/mermaid-cli 的 mmdc 命令。"""
    return _resolve_mmdc_executable() is not None


def _build_subprocess_env() -> dict[str, str]:
    """继承环境变量，并在未设置时自动补全 PUPPETEER_EXECUTABLE_PATH。"""
    env = os.environ.copy()
    if env.get("PUPPETEER_EXECUTABLE_PATH"):
        return env
    for candidate in _BROWSER_CANDIDATES:
        if candidate.is_file():
            env["PUPPETEER_EXECUTABLE_PATH"] = str(candidate)
            logger.info("[mermaid_renderer] 使用浏览器: %s", candidate)
            break
    return env


def _write_temp_mermaid(source: str) -> Path | None:
    """
    将 Mermaid 源码写入临时 .mmd 文件。
    返回临时文件路径；写入失败时返回 None（不抛异常）。
    """
    try:
        tmp = tempfile.NamedTemporaryFile(
            mode="w",
            suffix=".mmd",
            prefix="visionary_mermaid_",
            delete=False,
            encoding="utf-8",
        )
        with tmp:
            tmp.write(source)
            return Path(tmp.name)
    except OSError as exc:
        logger.warning("[mermaid_renderer] 无法创建临时 .mmd 文件: %s", exc)
        return None


def _cleanup_temp_file(path: Path | None) -> None:
    """安全删除临时文件，忽略已不存在或权限问题。"""
    if path is None:
        return
    try:
        path.unlink(missing_ok=True)
    except OSError as exc:
        logger.warning("[mermaid_renderer] 清理临时文件失败 (%s): %s", path, exc)


def _run_mmdc(input_path: Path, output_path: Path, timeout: int) -> bool:
    """
    一次性调用 mmdc 渲染 PNG。
    超时或进程异常时强杀子进程，返回 False。
    """
    mmdc_exe = _resolve_mmdc_executable()
    if not mmdc_exe:
        logger.warning("[mermaid_renderer] 无法解析 mmdc 可执行文件路径")
        return False

    cmd = [
        mmdc_exe,
        "-i",
        str(input_path),
        "-o",
        str(output_path),
        "-b",
        "transparent",
    ]
    env = _build_subprocess_env()

    # 使用 Popen + communicate，便于超时后显式 kill（Windows / Unix 均可靠）
    proc: subprocess.Popen[bytes] | None = None
    try:
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
        )
        _stdout, stderr = proc.communicate(timeout=timeout)

        if proc.returncode != 0:
            err_text = (stderr or b"").decode("utf-8", errors="replace").strip()
            logger.warning(
                "[mermaid_renderer] mmdc 退出码 %s: %s",
                proc.returncode,
                err_text or "(无 stderr)",
            )
            return False

        if not output_path.is_file() or output_path.stat().st_size == 0:
            logger.warning("[mermaid_renderer] mmdc 未生成有效 PNG: %s", output_path)
            return False

        return True

    except subprocess.TimeoutExpired:
        logger.warning("[mermaid_renderer] mmdc 渲染超时（>%ss），正在终止进程", timeout)
        if proc is not None:
            try:
                proc.kill()
                proc.wait(timeout=5)
            except (ProcessLookupError, subprocess.TimeoutExpired, OSError):
                pass
        return False

    except OSError as exc:
        # 含 FileNotFoundError：mmdc 在 race 条件下被卸载等极端情况
        logger.warning("[mermaid_renderer] 启动 mmdc 失败: %s", exc)
        if proc is not None and proc.poll() is None:
            try:
                proc.kill()
            except OSError:
                pass
        return False

    except Exception as exc:  # noqa: BLE001 — 导出链路要求吞掉一切，返回 False
        logger.warning("[mermaid_renderer] 渲染过程未预期异常: %s", exc)
        if proc is not None and proc.poll() is None:
            try:
                proc.kill()
            except OSError:
                pass
        return False


def render_mermaid_png(
    mermaid_code: str,
    output_png_path: str,
    timeout: int = 8,
) -> bool:
    """
    将 Mermaid 源码渲染为 PNG 文件。

    Args:
        mermaid_code: Mermaid 图表源码（如 ``graph TD; A-->B``）。
        output_png_path: 输出 PNG 的绝对或相对路径。
        timeout: 子进程硬性超时（秒），默认 8。超时后强杀 mmdc 并返回 False。

    Returns:
        True  — 渲染成功且输出文件非空。
        False — 环境缺失、入参无效、超时、进程崩溃或任何其他失败。
               本函数 **永不抛出异常**，上层可安全降级为纯文本填充。
    """
    # --- 入参校验 ---
    if not isinstance(mermaid_code, str) or not mermaid_code.strip():
        logger.warning("[mermaid_renderer] Mermaid 源码为空，跳过渲染")
        return False

    if timeout <= 0:
        logger.warning("[mermaid_renderer] timeout 必须为正整数，收到: %s", timeout)
        return False

    # --- 环境检测：未安装 mmdc 则直接降级 ---
    if not _is_mmdc_available():
        logger.warning(
            "[mermaid_renderer] 未检测到 mmdc（@mermaid-js/mermaid-cli）。"
            "请执行: npm i -g @mermaid-js/mermaid-cli"
        )
        return False

    output_path = Path(output_png_path)
    temp_mmd: Path | None = None

    try:
        # 确保输出目录存在
        try:
            output_path.parent.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            logger.warning("[mermaid_renderer] 无法创建输出目录 %s: %s", output_path.parent, exc)
            return False

        # 写入临时 .mmd 输入文件
        temp_mmd = _write_temp_mermaid(mermaid_code.strip())
        if temp_mmd is None:
            return False

        # 若目标已存在旧文件，先删除避免 mmdc 写入异常
        try:
            output_path.unlink(missing_ok=True)
        except OSError as exc:
            logger.warning("[mermaid_renderer] 无法清理旧输出文件 %s: %s", output_path, exc)
            return False

        # 一次性 subprocess 调用（无重试）
        return _run_mmdc(temp_mmd, output_path, timeout)

    finally:
        _cleanup_temp_file(temp_mmd)
