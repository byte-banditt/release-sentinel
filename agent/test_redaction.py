from redaction import redact_payload

def test_redacts_tokens_and_nested_ci_text():
    payload = {"logExcerpt": "token=abc123 Authorization: Bearer secret-value", "changedFiles": [{"patch": "password: hunter2\nAKIA1234567890ABCDEF"}]}
    safe = redact_payload(payload)
    text = str(safe)
    assert "abc123" not in text and "secret-value" not in text and "hunter2" not in text and "AKIA1234567890ABCDEF" not in text
    assert "<REDACTED>" in text

def test_tools_are_payload_only():
    from tools import get_diff_summary, get_log_excerpt
    payload = {"changedFiles": [{"path": "a.py", "status": "M", "patch": "+x\n-y"}], "logExcerpt": "failure"}
    assert get_diff_summary(payload) == [{"path": "a.py", "status": "M", "addedLines": 1, "removedLines": 1}]
    assert get_log_excerpt(payload) == "failure"
