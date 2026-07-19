#!/usr/bin/env python3
"""Premium 导出统一异常类型，供 Java fallback 识别。"""


class PremiumExportError(Exception):
    """Premium 导出链路任意步骤失败时抛出，触发 Java 侧静默降级。"""

    def __init__(self, code: str, message: str = "") -> None:
        self.code = code
        super().__init__(message or code)
