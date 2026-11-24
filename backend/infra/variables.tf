variable "aws_region" {
  description = "Single fixed AWS region allowed for Bedrock Mantle google.gemma-4-e2b inference (no cross-region inference)."
  type        = string
  default     = "us-east-1"

  validation {
    condition     = contains(["us-east-1", "us-east-2", "us-west-2"], var.aws_region)
    error_message = "aws_region must be pinned to a Bedrock Mantle Gemma 4 region (us-east-1, us-east-2, or us-west-2)."
  }
}

variable "project_name" {
  description = "Short resource prefix."
  type        = string
  default     = "meu-exame-vision-fallback"
}

variable "environment" {
  description = "Deployment stage name."
  type        = string
  default     = "prod"
}

variable "create_cognito_user_pool" {
  description = "Provision a lightweight Amazon Cognito User Pool & App Client to issue JWTs for the API Gateway authorizer."
  type        = bool
  default     = true
}

variable "jwt_issuer" {
  description = "External OIDC/JWT issuer URL when create_cognito_user_pool is false."
  type        = string
  default     = ""
}

variable "jwt_audience" {
  description = "Allowed JWT audiences (client IDs) when create_cognito_user_pool is false."
  type        = list(string)
  default     = []
}

variable "throttle_rate_limit" {
  description = "Steady-state requests per second allowed by API Gateway throttling."
  type        = number
  default     = 10
}

variable "throttle_burst_limit" {
  description = "Burst request capacity allowed by API Gateway throttling."
  type        = number
  default     = 20
}

variable "log_retention_days" {
  description = "Short retention window (days) for technical-only CloudWatch logs."
  type        = number
  default     = 14

  validation {
    condition     = var.log_retention_days >= 1 && var.log_retention_days <= 30
    error_message = "log_retention_days must stay between 1 and 30 days."
  }
}

variable "monthly_budget_usd" {
  description = "Monthly AWS cost threshold (USD) for Bedrock + Lambda + API Gateway alarm."
  type        = number
  default     = 25
}

variable "budget_alert_email" {
  description = "Optional email address to receive AWS Budgets threshold notifications."
  type        = string
  default     = ""
}
