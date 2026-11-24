# Meu Exame — Private Cloud Fallback Backend (AWS Lambda + Amazon Bedrock Mantle)

Stateless HTTPS fallback proxy backing the opt-in private cloud path when on-device `Gemma 4 E2B` (`LiteRT-LM`) is unavailable.

## Architecture & Privacy Posture

- **Model parity**: Amazon Bedrock Mantle OpenAI-compatible Chat Completions endpoint (`https://bedrock-mantle.<region>.api.aws/openai/v1/chat/completions`, model `google.gemma-4-e2b`, `temperature=0.0`, `max_tokens=1536`) using the exact `exam-vision/1` / `exam-vision-items/1` contract shared with on-device inference.
- **Strictly stateless**: AWS Lambda (Python 3.12) processes band images in RAM only — zero S3, zero database, zero disk persistence, `Cache-Control: no-store`.
- **No VPC / No AWS PrivateLink**: Lambda executes outside VPC (zero fixed hourly network cost) calling the regional Bedrock Mantle endpoint over TLS 1.2+/1.3 + AWS SigV4.
- **Regional pinning**: IAM policy restricts `bedrock-mantle:CreateInference` / `bedrock:InvokeModel` strictly to the single pinned `aws:RequestedRegion`. An AWS Organizations SCP example is provided in `infra/scp-regional-pinning.example.json`.
- **JWT auth & throttling**: API Gateway HTTP API v2 validates Bearer JWT tokens (via a lightweight Cognito User Pool provisioned by default or an external OIDC issuer) and enforces per-stage rate/burst throttling.
- **Sanitized observability**: Amazon Bedrock *Model Invocation Logging* MUST remain disabled. CloudWatch logs retain only technical metrics (`requestId`, `statusCode`, `latencyMs`, `inputTokens`, `outputTokens`, `errorCode`) for 14 days.

## Local Verification & Deploy

```bash
# 1. Lambda unit & privacy-log tests
python3 -m unittest discover -s backend/lambda -p "test_*.py"

# 2. Terraform formatting, validation & deploy
terraform -chdir=backend/infra fmt -check
terraform -chdir=backend/infra init
terraform -chdir=backend/infra validate
AWS_PROFILE=<your-profile> terraform -chdir=backend/infra apply -var="aws_region=us-east-1"
```
