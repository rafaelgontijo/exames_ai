"""
Stateless AWS Lambda proxy for Meu Exame opt-in private cloud fallback.

Privacy & governance invariants:
- Strictly stateless: processes payloads only in memory, zero disk/S3/database writes.
- Regional pinning: invokes Amazon Bedrock Mantle (`google.gemma-4-e2b`) at
  `https://bedrock-mantle.<region>.api.aws/openai/v1/chat/completions` over TLS + AWS SigV4
  in a single allowed AWS region.
- Sanitized observability: logs ONLY technical metrics (requestId, statusCode, latencyMs,
  inputTokens, outputTokens, errorCode). Never logs request bodies, images, prompts, or responses.
"""

from __future__ import annotations

import base64
import binascii
import json
import logging
import os
import socket
import time
import urllib.error
import urllib.request
from typing import Any, Callable

try:
    import boto3
    from botocore.auth import SigV4Auth
    from botocore.awsrequest import AWSRequest
except ImportError:  # pragma: no cover - local unit tests may run without boto3 installed
    boto3 = None
    SigV4Auth = None
    AWSRequest = None


MODEL_ID = os.environ.get("MODEL_ID", "google.gemma-4-e2b")
BEDROCK_REGION = os.environ.get("BEDROCK_REGION", os.environ.get("AWS_REGION", "us-east-1"))
ALLOWED_REGIONS = {
    region.strip()
    for region in os.environ.get("ALLOWED_REGIONS", BEDROCK_REGION).split(",")
    if region.strip()
}
SIGV4_SERVICE = os.environ.get("BEDROCK_SIGV4_SERVICE", "bedrock-mantle")
UPSTREAM_TIMEOUT_SECONDS = float(os.environ.get("UPSTREAM_TIMEOUT_SECONDS", "35"))

ALLOWED_PROMPT_VERSIONS = frozenset({"exam-vision/1"})
ALLOWED_SCHEMA_VERSIONS = frozenset({"exam-vision-items/1"})
ALLOWED_MIME_TYPES = frozenset({"image/png", "image/jpeg"})

MAX_REQUEST_BODY_BYTES = 1024 * 1024  # 1 MiB raw JSON envelope
MAX_IMAGE_BYTES = 512 * 1024  # 512 KiB decoded PNG/JPEG band
MAX_INSTRUCTION_CHARS = 6_000
MAX_DOCUMENT_TEXT_CHARS = 20_000
MAX_OUTPUT_TOKENS = 1536
TEMPERATURE = 0.0

RESPONSE_HEADERS = {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "Pragma": "no-cache",
    "X-Content-Type-Options": "nosniff",
}

logger = logging.getLogger("exames.vision_fallback")
logger.setLevel(logging.INFO)
if not logger.handlers and not logging.getLogger().handlers:
    logging.basicConfig(level=logging.INFO, format="%(message)s")

_cached_session: Any = None


class UpstreamError(Exception):
    def __init__(self, status_code: int, error_code: str, message: str) -> None:
        super().__init__(error_code)
        self.status_code = status_code
        self.error_code = error_code
        self.message = message


def _get_boto_session() -> Any:
    global _cached_session
    if _cached_session is None:
        if boto3 is None:
            raise RuntimeError("boto3 is unavailable in this environment")
        _cached_session = boto3.Session(region_name=BEDROCK_REGION)
    return _cached_session


def _log_metric(
    *,
    request_id: str,
    status_code: int,
    latency_ms: int,
    input_tokens: int = 0,
    output_tokens: int = 0,
    error_code: str | None = None,
) -> None:
    record = {
        "event": "vision_extract",
        "requestId": request_id,
        "statusCode": status_code,
        "latencyMs": latency_ms,
        "inputTokens": input_tokens,
        "outputTokens": output_tokens,
        "errorCode": error_code,
    }
    logger.info(json.dumps(record, separators=(",", ":")))


def _error_response(
    *,
    request_id: str,
    status_code: int,
    error_code: str,
    message: str,
    start_monotonic: float,
) -> dict[str, Any]:
    latency_ms = max(0, int((time.monotonic() - start_monotonic) * 1000))
    _log_metric(
        request_id=request_id,
        status_code=status_code,
        latency_ms=latency_ms,
        error_code=error_code,
    )
    return {
        "statusCode": status_code,
        "headers": RESPONSE_HEADERS,
        "body": json.dumps(
            {
                "errorCode": error_code,
                "message": message,
                "requestId": request_id,
            },
            separators=(",", ":"),
        ),
    }


def _extract_request_id(event: dict[str, Any], context: Any) -> str:
    ctx = event.get("requestContext") or {}
    if isinstance(ctx, dict) and ctx.get("requestId"):
        return str(ctx["requestId"])
    if context is not None and getattr(context, "aws_request_id", None):
        return str(context.aws_request_id)
    return "unknown"


def _parse_body(event: dict[str, Any]) -> tuple[dict[str, Any] | None, tuple[int, str, str] | None]:
    raw_body = event.get("body")
    if raw_body is None:
        return None, (400, "INVALID_REQUEST", "Request body is required.")

    if isinstance(raw_body, bytes):
        body_bytes = raw_body
    elif isinstance(raw_body, str):
        if event.get("isBase64Encoded"):
            try:
                body_bytes = base64.b64decode(raw_body, validate=True)
            except (binascii.Error, ValueError):
                return None, (400, "INVALID_BASE64", "Invalid base64 request envelope.")
        else:
            body_bytes = raw_body.encode("utf-8")
    else:
        return None, (400, "INVALID_REQUEST", "Unsupported request body type.")

    if len(body_bytes) > MAX_REQUEST_BODY_BYTES:
        return None, (413, "PAYLOAD_TOO_LARGE", "Request envelope exceeds 1 MiB limit.")

    try:
        parsed = json.loads(body_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None, (400, "INVALID_JSON", "Malformed JSON request body.")

    if not isinstance(parsed, dict):
        return None, (400, "INVALID_JSON", "JSON request body must be an object.")
    return parsed, None


def _validate_payload(
    payload: dict[str, Any],
) -> tuple[dict[str, Any] | None, tuple[int, str, str] | None]:
    prompt_version = payload.get("promptVersion")
    schema_version = payload.get("schemaVersion")
    if prompt_version not in ALLOWED_PROMPT_VERSIONS:
        return None, (400, "UNSUPPORTED_PROMPT_VERSION", "Unsupported promptVersion.")
    if schema_version not in ALLOWED_SCHEMA_VERSIONS:
        return None, (400, "UNSUPPORTED_SCHEMA_VERSION", "Unsupported schemaVersion.")

    instruction = payload.get("instruction")
    document_text = payload.get("documentText", "")
    if not isinstance(instruction, str) or not instruction.strip() or len(instruction) > MAX_INSTRUCTION_CHARS:
        return None, (400, "INVALID_INSTRUCTION", "Instruction is required and must stay within size limits.")
    if not isinstance(document_text, str) or len(document_text) > MAX_DOCUMENT_TEXT_CHARS:
        return None, (400, "INVALID_DOCUMENT_TEXT", "documentText must be a string within size limits.")

    image_mime = payload.get("imageMimeType")
    if not isinstance(image_mime, str) or image_mime not in ALLOWED_MIME_TYPES:
        return None, (400, "UNSUPPORTED_IMAGE_MIME", "Only image/png and image/jpeg are supported.")

    image_b64 = payload.get("imageBase64")
    if not isinstance(image_b64, str) or not image_b64.strip():
        return None, (400, "MISSING_IMAGE", "imageBase64 is required.")

    try:
        image_bytes = base64.b64decode(image_b64, validate=True)
    except (binascii.Error, ValueError):
        return None, (400, "INVALID_IMAGE_BASE64", "imageBase64 is not valid base64.")

    if not image_bytes:
        return None, (400, "EMPTY_IMAGE", "Decoded image cannot be empty.")
    if len(image_bytes) > MAX_IMAGE_BYTES:
        return None, (413, "IMAGE_TOO_LARGE", "Decoded image exceeds 512 KiB limit.")

    canonical_b64 = base64.b64encode(image_bytes).decode("ascii")
    return {
        "instruction": instruction,
        "document_text": document_text,
        "image_mime": image_mime,
        "image_b64": canonical_b64,
    }, None


def _invoke_bedrock_mantle(region: str, request_payload: dict[str, Any]) -> dict[str, Any]:
    if SigV4Auth is None or AWSRequest is None:
        raise UpstreamError(502, "UPSTREAM_UNAVAILABLE", "AWS SigV4 signer unavailable.")

    url = f"https://bedrock-mantle.{region}.api.aws/openai/v1/chat/completions"
    body_bytes = json.dumps(request_payload, separators=(",", ":")).encode("utf-8")

    session = _get_boto_session()
    credentials = session.get_credentials()
    if credentials is None:
        raise UpstreamError(502, "UPSTREAM_CREDENTIALS", "AWS execution credentials unavailable.")

    aws_request = AWSRequest(
        method="POST",
        url=url,
        data=body_bytes,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )
    SigV4Auth(credentials.get_frozen_credentials(), SIGV4_SERVICE, region).add_auth(aws_request)

    http_req = urllib.request.Request(
        url=url,
        data=body_bytes,
        headers=dict(aws_request.headers.items()),
        method="POST",
    )
    try:
        with urllib.request.urlopen(http_req, timeout=UPSTREAM_TIMEOUT_SECONDS) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw)
    except urllib.error.HTTPError as exc:
        code = exc.code
        if code == 429:
            raise UpstreamError(429, "RATE_LIMITED", "Upstream inference rate limit exceeded.") from None
        if code in (401, 403):
            raise UpstreamError(403, "UPSTREAM_FORBIDDEN", "Upstream inference access denied.") from None
        if code == 400:
            raise UpstreamError(400, "UPSTREAM_VALIDATION", "Upstream rejected request parameters.") from None
        if code == 504:
            raise UpstreamError(504, "UPSTREAM_TIMEOUT", "Upstream inference timed out.") from None
        raise UpstreamError(502, "UPSTREAM_ERROR", "Upstream inference service error.") from None
    except (socket.timeout, TimeoutError):
        raise UpstreamError(504, "UPSTREAM_TIMEOUT", "Inference request timed out.") from None
    except urllib.error.URLError as exc:
        if isinstance(exc.reason, (socket.timeout, TimeoutError)):
            raise UpstreamError(504, "UPSTREAM_TIMEOUT", "Inference request timed out.") from None
        raise UpstreamError(502, "UPSTREAM_UNAVAILABLE", "Upstream inference service unavailable.") from None
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise UpstreamError(502, "UPSTREAM_INVALID_JSON", "Upstream returned malformed JSON.") from None


def _extract_text_from_chat_completion(response: dict[str, Any]) -> str:
    choices = response.get("choices") or []
    if not choices or not isinstance(choices, list):
        return ""
    first = choices[0] if isinstance(choices[0], dict) else {}
    message = first.get("message") or {}
    content = message.get("content")
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts = [
            part.get("text", "")
            for part in content
            if isinstance(part, dict) and isinstance(part.get("text"), str)
        ]
        return "".join(parts).strip()
    return ""


def _extract_usage(response: dict[str, Any]) -> tuple[int, int]:
    usage = response.get("usage") or {}
    input_tokens = int(usage.get("prompt_tokens", usage.get("inputTokens", 0)) or 0)
    output_tokens = int(usage.get("completion_tokens", usage.get("outputTokens", 0)) or 0)
    return input_tokens, output_tokens


def lambda_handler(
    event: dict[str, Any],
    context: Any = None,
    *,
    upstream_invoker: Callable[[str, dict[str, Any]], dict[str, Any]] | None = None,
    bedrock_region: str | None = None,
) -> dict[str, Any]:
    start_monotonic = time.monotonic()
    request_id = _extract_request_id(event, context)
    active_region = bedrock_region or BEDROCK_REGION

    if active_region not in ALLOWED_REGIONS:
        return _error_response(
            request_id=request_id,
            status_code=403,
            error_code="REGION_NOT_ALLOWED",
            message="Configured inference region is not permitted.",
            start_monotonic=start_monotonic,
        )

    payload, body_err = _parse_body(event)
    if body_err is not None or payload is None:
        status, code, msg = body_err or (400, "INVALID_REQUEST", "Invalid request.")
        return _error_response(
            request_id=request_id,
            status_code=status,
            error_code=code,
            message=msg,
            start_monotonic=start_monotonic,
        )

    validated, val_err = _validate_payload(payload)
    if val_err is not None or validated is None:
        status, code, msg = val_err or (400, "INVALID_REQUEST", "Invalid payload.")
        return _error_response(
            request_id=request_id,
            status_code=status,
            error_code=code,
            message=msg,
            start_monotonic=start_monotonic,
        )

    user_text = (
        validated["document_text"].strip()
        or "Extraia os itens visíveis nesta faixa do laudo no formato JSON solicitado."
    )
    mantle_payload = {
        "model": MODEL_ID,
        "temperature": TEMPERATURE,
        "max_tokens": MAX_OUTPUT_TOKENS,
        "messages": [
            {"role": "system", "content": validated["instruction"]},
            {
                "role": "user",
                "content": [
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:{validated['image_mime']};base64,{validated['image_b64']}"
                        },
                    },
                    {"type": "text", "text": user_text},
                ],
            },
        ],
    }

    invoker = upstream_invoker if upstream_invoker is not None else _invoke_bedrock_mantle
    try:
        response = invoker(active_region, mantle_payload)
    except UpstreamError as exc:
        return _error_response(
            request_id=request_id,
            status_code=exc.status_code,
            error_code=exc.error_code,
            message=exc.message,
            start_monotonic=start_monotonic,
        )
    except TimeoutError:
        return _error_response(
            request_id=request_id,
            status_code=504,
            error_code="UPSTREAM_TIMEOUT",
            message="Inference request timed out.",
            start_monotonic=start_monotonic,
        )
    except Exception:
        return _error_response(
            request_id=request_id,
            status_code=502,
            error_code="UPSTREAM_ERROR",
            message="Upstream inference service unavailable.",
            start_monotonic=start_monotonic,
        )

    text = _extract_text_from_chat_completion(response)
    input_tokens, output_tokens = _extract_usage(response)
    latency_ms = max(0, int((time.monotonic() - start_monotonic) * 1000))

    if not text:
        _log_metric(
            request_id=request_id,
            status_code=502,
            latency_ms=latency_ms,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            error_code="EMPTY_MODEL_RESPONSE",
        )
        return {
            "statusCode": 502,
            "headers": RESPONSE_HEADERS,
            "body": json.dumps(
                {
                    "errorCode": "EMPTY_MODEL_RESPONSE",
                    "message": "Model returned an empty response.",
                    "requestId": request_id,
                },
                separators=(",", ":"),
            ),
        }

    _log_metric(
        request_id=request_id,
        status_code=200,
        latency_ms=latency_ms,
        input_tokens=input_tokens,
        output_tokens=output_tokens,
        error_code=None,
    )
    return {
        "statusCode": 200,
        "headers": RESPONSE_HEADERS,
        "body": json.dumps(
            {
                "text": text,
                "usage": {
                    "inputTokens": input_tokens,
                    "outputTokens": output_tokens,
                },
                "latencyMs": latency_ms,
            },
            separators=(",", ":"),
        ),
    }
