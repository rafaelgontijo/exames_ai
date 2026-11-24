import base64
import json
import logging
import unittest
from typing import Any

import handler


def _make_event(body_obj: Any, request_id: str = "req-123") -> dict[str, Any]:
    return {
        "requestContext": {"requestId": request_id},
        "body": json.dumps(body_obj) if not isinstance(body_obj, str) else body_obj,
        "isBase64Encoded": False,
    }


def _valid_payload(**overrides: Any) -> dict[str, Any]:
    png_bytes = b"\x89PNG\r\n\x1a\n" + b"synthetic-band-data"
    base = {
        "promptVersion": "exam-vision/1",
        "schemaVersion": "exam-vision-items/1",
        "instruction": "Return JSON with items array.",
        "documentText": "",
        "imageMimeType": "image/png",
        "imageBase64": base64.b64encode(png_bytes).decode("ascii"),
    }
    base.update(overrides)
    return base


class FakeMantleInvoker:
    def __init__(self, response: dict[str, Any] | None = None, error: Exception | None = None) -> None:
        self.response = response
        self.error = error
        self.last_region: str | None = None
        self.last_payload: dict[str, Any] | None = None

    def __call__(self, region: str, payload: dict[str, Any]) -> dict[str, Any]:
        self.last_region = region
        self.last_payload = payload
        if self.error is not None:
            raise self.error
        return self.response or {}


class VisionFallbackHandlerTest(unittest.TestCase):
    def test_valid_png_request_invokes_gemma_4_e2b_mantle_and_returns_structured_response(self) -> None:
        fake = FakeMantleInvoker(
            response={
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {
                            "role": "assistant",
                            "content": '{"items":[{"name":"Item A","value":"10","unit":"mg/dL"}]}',
                        },
                    }
                ],
                "usage": {"prompt_tokens": 420, "completion_tokens": 55},
            }
        )

        with self.assertLogs("exames.vision_fallback", level="INFO") as logs:
            res = handler.lambda_handler(_make_event(_valid_payload()), upstream_invoker=fake)

        self.assertEqual(res["statusCode"], 200)
        self.assertEqual(res["headers"]["Cache-Control"], "no-store")
        body = json.loads(res["body"])
        self.assertIn("Item A", body["text"])
        self.assertEqual(body["usage"], {"inputTokens": 420, "outputTokens": 55})
        self.assertGreaterEqual(body["latencyMs"], 0)

        self.assertEqual(fake.last_region, handler.BEDROCK_REGION)
        assert fake.last_payload is not None
        self.assertEqual(fake.last_payload["model"], "google.gemma-4-e2b")
        self.assertEqual(fake.last_payload["temperature"], 0.0)
        self.assertEqual(fake.last_payload["max_tokens"], 1536)
        user_content = fake.last_payload["messages"][1]["content"]
        self.assertEqual(user_content[0]["type"], "image_url")
        self.assertTrue(user_content[0]["image_url"]["url"].startswith("data:image/png;base64,"))

        log_json = json.loads(logs.output[0].split("INFO:exames.vision_fallback:", 1)[-1])
        self.assertEqual(log_json["statusCode"], 200)
        self.assertEqual(log_json["inputTokens"], 420)
        self.assertEqual(log_json["outputTokens"], 55)
        self.assertIsNone(log_json["errorCode"])

    def test_valid_jpeg_request_maps_to_jpeg_data_url(self) -> None:
        fake = FakeMantleInvoker(
            response={
                "choices": [{"message": {"content": '{"items":[]}'}}],
                "usage": {"prompt_tokens": 100, "completion_tokens": 10},
            }
        )
        res = handler.lambda_handler(
            _make_event(_valid_payload(imageMimeType="image/jpeg")),
            upstream_invoker=fake,
        )
        self.assertEqual(res["statusCode"], 200)
        assert fake.last_payload is not None
        url = fake.last_payload["messages"][1]["content"][0]["image_url"]["url"]
        self.assertTrue(url.startswith("data:image/jpeg;base64,"))

    def test_rejects_unsupported_prompt_or_schema_version(self) -> None:
        fake = FakeMantleInvoker()
        res_prompt = handler.lambda_handler(
            _make_event(_valid_payload(promptVersion="legacy/0")),
            upstream_invoker=fake,
        )
        res_schema = handler.lambda_handler(
            _make_event(_valid_payload(schemaVersion="other/2")),
            upstream_invoker=fake,
        )
        self.assertEqual(res_prompt["statusCode"], 400)
        self.assertEqual(res_schema["statusCode"], 400)
        self.assertIsNone(fake.last_payload)

    def test_rejects_unsupported_mime_type_and_invalid_base64(self) -> None:
        fake = FakeMantleInvoker()
        res_mime = handler.lambda_handler(
            _make_event(_valid_payload(imageMimeType="image/gif")),
            upstream_invoker=fake,
        )
        res_b64 = handler.lambda_handler(
            _make_event(_valid_payload(imageBase64="!!!not-base64!!!")),
            upstream_invoker=fake,
        )
        self.assertEqual(res_mime["statusCode"], 400)
        self.assertEqual(res_b64["statusCode"], 400)
        self.assertIsNone(fake.last_payload)

    def test_rejects_image_larger_than_512_kib_with_413(self) -> None:
        fake = FakeMantleInvoker()
        oversized = base64.b64encode(b"x" * (512 * 1024 + 1)).decode("ascii")
        res = handler.lambda_handler(
            _make_event(_valid_payload(imageBase64=oversized)),
            upstream_invoker=fake,
        )
        self.assertEqual(res["statusCode"], 413)
        self.assertEqual(json.loads(res["body"])["errorCode"], "IMAGE_TOO_LARGE")
        self.assertIsNone(fake.last_payload)

    def test_maps_upstream_throttling_timeout_and_empty_output(self) -> None:
        throttled = handler.lambda_handler(
            _make_event(_valid_payload()),
            upstream_invoker=FakeMantleInvoker(
                error=handler.UpstreamError(429, "RATE_LIMITED", "Rate limited.")
            ),
        )
        self.assertEqual(throttled["statusCode"], 429)

        timed_out = handler.lambda_handler(
            _make_event(_valid_payload()),
            upstream_invoker=FakeMantleInvoker(error=TimeoutError("read timed out")),
        )
        self.assertEqual(timed_out["statusCode"], 504)

        empty = handler.lambda_handler(
            _make_event(_valid_payload()),
            upstream_invoker=FakeMantleInvoker(
                response={"choices": [{"message": {"content": "   "}}]}
            ),
        )
        self.assertEqual(empty["statusCode"], 502)

    def test_blocks_disallowed_region(self) -> None:
        fake = FakeMantleInvoker()
        res = handler.lambda_handler(
            _make_event(_valid_payload()),
            upstream_invoker=fake,
            bedrock_region="eu-central-1",
        )
        self.assertEqual(res["statusCode"], 403)
        self.assertEqual(json.loads(res["body"])["errorCode"], "REGION_NOT_ALLOWED")
        self.assertIsNone(fake.last_payload)

    def test_logs_and_error_responses_never_leak_clinical_payload_or_image(self) -> None:
        secret_instruction = "SENTINEL_INSTRUCTION_SECRET_991"
        secret_doc = "SENTINEL_DOC_CPF_12345678900"
        secret_image_b64 = base64.b64encode(b"SENTINEL_IMAGE_BYTES_XYZ").decode("ascii")
        secret_output = '{"items":[{"name":"SENTINEL_CLINICAL_VALUE_777"}]}'

        ok_invoker = FakeMantleInvoker(
            response={
                "choices": [{"message": {"content": secret_output}}],
                "usage": {"prompt_tokens": 300, "completion_tokens": 40},
            }
        )
        err_invoker = FakeMantleInvoker(error=RuntimeError("SENTINEL_INTERNAL_ERROR_DETAIL"))

        with self.assertLogs("exames.vision_fallback", level=logging.INFO) as logs:
            res_ok = handler.lambda_handler(
                _make_event(
                    _valid_payload(
                        instruction=secret_instruction,
                        documentText=secret_doc,
                        imageBase64=secret_image_b64,
                    )
                ),
                upstream_invoker=ok_invoker,
            )
            res_err = handler.lambda_handler(
                _make_event(
                    _valid_payload(
                        instruction=secret_instruction,
                        documentText=secret_doc,
                        imageBase64=secret_image_b64,
                    )
                ),
                upstream_invoker=err_invoker,
            )

        self.assertEqual(res_ok["statusCode"], 200)
        self.assertEqual(res_err["statusCode"], 502)

        all_logs = "\n".join(logs.output)
        for sentinel in (
            secret_instruction,
            secret_doc,
            secret_image_b64,
            "SENTINEL_CLINICAL_VALUE_777",
            "SENTINEL_INTERNAL_ERROR_DETAIL",
        ):
            self.assertNotIn(sentinel, all_logs)
            self.assertNotIn(sentinel, res_err["body"])


if __name__ == "__main__":
    unittest.main()
