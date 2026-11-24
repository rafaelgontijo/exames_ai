output "api_endpoint" {
  description = "Base HTTPS endpoint of the API Gateway HTTP API."
  value       = aws_apigatewayv2_api.vision.api_endpoint
}

output "extract_route_url" {
  description = "Full HTTPS URL for POST /v1/vision/extract to configure in Android examesCloudFallbackUrl."
  value       = "${aws_apigatewayv2_api.vision.api_endpoint}/v1/vision/extract"
}

output "lambda_function_name" {
  description = "Name of the stateless Lambda proxy function."
  value       = aws_lambda_function.vision_fallback.function_name
}

output "bedrock_mantle_endpoint" {
  description = "Regional Amazon Bedrock Mantle endpoint pinned in Lambda."
  value       = local.bedrock_mantle_url
}

output "cognito_user_pool_id" {
  description = "Cognito User Pool ID (when create_cognito_user_pool = true)."
  value       = var.create_cognito_user_pool ? aws_cognito_user_pool.auth[0].id : null
}

output "cognito_client_id" {
  description = "Cognito App Client ID (JWT audience when create_cognito_user_pool = true)."
  value       = var.create_cognito_user_pool ? aws_cognito_user_pool_client.mobile[0].id : null
}

output "jwt_issuer" {
  description = "Effective JWT issuer configured in API Gateway."
  value       = local.effective_jwt_issuer
}

output "privacy_posture" {
  description = "Summary of enforced backend privacy controls."
  value = {
    stateless              = true
    vpc_privatelink        = false
    model_invocation_log   = "disabled"
    regional_pinning       = var.aws_region
    log_retention_days     = var.log_retention_days
    zero_payload_in_logs   = true
    cross_region_inference = false
  }
}
