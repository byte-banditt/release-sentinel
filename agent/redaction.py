"""Redact credentials before untrusted CI text reaches the model."""
import re
from typing import Any

_ASSIGNMENT = re.compile(r"(?i)\b(password|passwd|token|secret|api[_-]?key|authorization)\b(\s*[:=]\s*)((?:Bearer\s+)?[^\s,;]+)")
_BEARER = re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+")
_JWT = re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b")
_AWS_KEY = re.compile(r"\bAKIA[0-9A-Z]{16}\b")

def redact_text(value: str) -> str:
    value = _ASSIGNMENT.sub(lambda m: f"{m.group(1)}{m.group(2)}<REDACTED>", value)
    value = _BEARER.sub("Bearer <REDACTED>", value)
    value = _JWT.sub("<REDACTED_JWT>", value)
    return _AWS_KEY.sub("<REDACTED_AWS_KEY>", value)

def redact_payload(payload: dict[str, Any]) -> dict[str, Any]:
    def visit(value: Any) -> Any:
        if isinstance(value, str): return redact_text(value)
        if isinstance(value, list): return [visit(item) for item in value]
        if isinstance(value, dict): return {key: visit(item) for key, item in value.items()}
        return value
    return visit(payload)
