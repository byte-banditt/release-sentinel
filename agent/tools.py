"""Read-only tools. They inspect only the invocation payload."""
from typing import Any

def get_diff_summary(payload: dict[str, Any]) -> list[dict[str, Any]]:
    result = []
    for changed in payload.get("changedFiles", []):
        patch = changed.get("patch", "")
        result.append({"path": changed.get("path", ""), "status": changed.get("status", ""),
                       "addedLines": sum(line.startswith("+") and not line.startswith("++") for line in patch.splitlines()),
                       "removedLines": sum(line.startswith("-") and not line.startswith("--") for line in patch.splitlines())})
    return result

def get_log_excerpt(payload: dict[str, Any]) -> str:
    return str(payload.get("logExcerpt", ""))[:12000]
