# release-sentinel AgentCore Runtime

This is a stateless HTTP AgentCore Runtime project using the official `bedrock-agentcore-starter-toolkit` `0.3.14`. The toolkit's current commands are `agentcore configure`, `agentcore deploy`, and `agentcore invoke`; new AWS projects are directed to the AgentCore CLI, but S3 intentionally keeps the requested starter toolkit.

Manual deployment is in `deploy-real-runtime.sh`; it is not a CI step. Before the first deploy, create an AWS Budget monthly cost alert in Billing with notifications at 50% and 80%. Confirm the selected `BEDROCK_MODEL_ID` has model access in `AWS_REGION`; third-party model access/EULA/IAM requirements vary by region and model.

Runtime flow: configure the entrypoint, deploy the Python CodeZip Runtime and endpoint, wait for endpoint `READY`, then invoke with a runtime session id of at least 33 characters when calling `InvokeAgentRuntime` directly. The starter toolkit performs these control-plane details for `agentcore deploy` and stores runtime metadata in `.bedrock_agentcore.yaml`.

Cost model: AgentCore Runtime microVMs bill actual CPU and memory consumption per second with a 1-second minimum and 128 MB minimum memory billing; CPU can scale to zero during I/O wait and Runtime v2 reclaims idle memory after 120 seconds. Code storage/ECR, network transfer, and Bedrock model input/output tokens are separate charges. AgentCore Instances use EC2 cost plus a management fee. Prices vary by region; recheck the official pricing page before deployment.

The agent redacts token, secret, password, authorization, JWT, and AWS key patterns before model input. `get_diff_summary` and `get_log_excerpt` only inspect that provided payload; they do not access network, files, or write tools. CI text is untrusted data and cannot override the system instruction or lower a rules-derived `BLOCK`.

Official references checked for this task:

- Runtime getting started: https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/runtime-getting-started.html
- `InvokeAgentRuntime`: https://docs.aws.amazon.com/bedrock-agentcore/latest/APIReference/API_InvokeAgentRuntime.html
- Model access: https://docs.aws.amazon.com/bedrock/latest/userguide/model-access.html
- AgentCore pricing: https://aws.amazon.com/bedrock/agentcore/pricing/
