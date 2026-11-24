locals {
  name_prefix            = "${var.project_name}-${var.environment}"
  bedrock_model_id       = "google.gemma-4-e2b"
  bedrock_mantle_url     = "https://bedrock-mantle.${var.aws_region}.api.aws/openai/v1/chat/completions"
  effective_jwt_issuer   = var.create_cognito_user_pool ? "https://${aws_cognito_user_pool.auth[0].endpoint}" : var.jwt_issuer
  effective_jwt_audience = var.create_cognito_user_pool ? [aws_cognito_user_pool_client.mobile[0].id] : var.jwt_audience
}

data "aws_caller_identity" "current" {}

data "archive_file" "lambda_package" {
  type        = "zip"
  source_file = "${path.module}/../lambda/handler.py"
  output_path = "${path.module}/.build/vision_fallback_lambda.zip"
}

# Optional lightweight Cognito User Pool (free tier up to 10k MAUs) for JWT authentication.
resource "aws_cognito_user_pool" "auth" {
  count = var.create_cognito_user_pool ? 1 : 0
  name  = "${local.name_prefix}-users"

  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]

  password_policy {
    minimum_length    = 12
    require_lowercase = true
    require_numbers   = true
    require_symbols   = true
    require_uppercase = true
  }
}

resource "aws_cognito_user_pool_client" "mobile" {
  count        = var.create_cognito_user_pool ? 1 : 0
  name         = "${local.name_prefix}-mobile-client"
  user_pool_id = aws_cognito_user_pool.auth[0].id

  generate_secret               = false
  prevent_user_existence_errors = "ENABLED"
  explicit_auth_flows = [
    "ALLOW_USER_PASSWORD_AUTH",
    "ALLOW_USER_SRP_AUTH",
    "ALLOW_REFRESH_TOKEN_AUTH",
  ]
}

# Short-retention technical-only logs (zero request/response bodies or PII).
resource "aws_cloudwatch_log_group" "lambda" {
  name              = "/aws/lambda/${local.name_prefix}"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_log_group" "apigw" {
  name              = "/aws/apigateway/${local.name_prefix}"
  retention_in_days = var.log_retention_days
}

resource "aws_iam_role" "lambda_exec" {
  name = "${local.name_prefix}-lambda-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Service = "lambda.amazonaws.com" }
        Action    = "sts:AssumeRole"
      }
    ]
  })
}

# Least-privilege IAM policy:
# - Allows Amazon Bedrock Mantle inference strictly in the single pinned region (blocks cross-region inference).
# - Explicitly denies Model Invocation Logging changes and any S3/DynamoDB persistence.
resource "aws_iam_role_policy" "lambda_policy" {
  name = "${local.name_prefix}-least-privilege"
  role = aws_iam_role.lambda_exec.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "InvokeRegionalBedrockMantleOnly"
        Effect = "Allow"
        Action = [
          "bedrock-mantle:CreateInference",
          "bedrock:InvokeModel"
        ]
        Resource = "*"
        Condition = {
          StringEquals = {
            "aws:RequestedRegion" = var.aws_region
          }
        }
      },
      {
        Sid    = "WriteSanitizedTechnicalLogsOnly"
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "${aws_cloudwatch_log_group.lambda.arn}:*"
      },
      {
        Sid    = "DenyPersistenceAndBedrockInvocationLogging"
        Effect = "Deny"
        Action = [
          "bedrock:PutModelInvocationLoggingConfiguration",
          "s3:*",
          "dynamodb:*"
        ]
        Resource = "*"
      }
    ]
  })
}

# Stateless Lambda proxy running outside VPC (zero fixed network cost).
# Communicates with Amazon Bedrock Mantle over TLS 1.2+/1.3 + AWS SigV4.
# Bedrock Model Invocation Logging MUST remain disabled at the AWS account/region level.
resource "aws_lambda_function" "vision_fallback" {
  function_name    = local.name_prefix
  role             = aws_iam_role.lambda_exec.arn
  runtime          = "python3.12"
  handler          = "handler.lambda_handler"
  filename         = data.archive_file.lambda_package.output_path
  source_code_hash = data.archive_file.lambda_package.output_base64sha256
  memory_size      = 512
  timeout          = 45

  environment {
    variables = {
      MODEL_ID              = local.bedrock_model_id
      BEDROCK_REGION        = var.aws_region
      ALLOWED_REGIONS       = var.aws_region
      BEDROCK_SIGV4_SERVICE = "bedrock-mantle"
    }
  }

  depends_on = [
    aws_cloudwatch_log_group.lambda,
    aws_iam_role_policy.lambda_policy,
  ]
}

resource "aws_apigatewayv2_api" "vision" {
  name          = local.name_prefix
  protocol_type = "HTTP"
  description   = "Stateless HTTPS (TLS 1.2+) opt-in fallback proxy for Meu Exame."
}

resource "aws_apigatewayv2_authorizer" "jwt" {
  api_id           = aws_apigatewayv2_api.vision.id
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]
  name             = "${local.name_prefix}-jwt"

  jwt_configuration {
    audience = local.effective_jwt_audience
    issuer   = local.effective_jwt_issuer
  }
}

resource "aws_apigatewayv2_integration" "lambda" {
  api_id                 = aws_apigatewayv2_api.vision.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.vision_fallback.invoke_arn
  payload_format_version = "2.0"
  timeout_milliseconds   = 30000
}

resource "aws_apigatewayv2_route" "extract" {
  api_id             = aws_apigatewayv2_api.vision.id
  route_key          = "POST /v1/vision/extract"
  target             = "integrations/${aws_apigatewayv2_integration.lambda.id}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.jwt.id
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.vision.id
  name        = "$default"
  auto_deploy = true

  default_route_settings {
    throttling_burst_limit = var.throttle_burst_limit
    throttling_rate_limit  = var.throttle_rate_limit
  }

  # Technical-only access logs: excludes request/response bodies, query strings, and auth headers.
  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.apigw.arn
    format = jsonencode({
      requestId       = "$context.requestId"
      routeKey        = "$context.routeKey"
      status          = "$context.status"
      responseLatency = "$context.responseLatency"
      integrationErr  = "$context.integrationErrorMessage"
    })
  }
}

resource "aws_lambda_permission" "apigw_invoke" {
  statement_id  = "AllowAPIGatewayV2Invoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.vision_fallback.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.vision.execution_arn}/*/*"
}

resource "aws_budgets_budget" "monthly_cost" {
  count        = var.budget_alert_email != "" ? 1 : 0
  name         = "${local.name_prefix}-monthly-usd"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_alert_email]
  }
}
