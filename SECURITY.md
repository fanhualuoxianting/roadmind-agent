# Security Policy

## Project scope

RoadMind Agent is a digital-twin engineering demo. It does not connect to a real vehicle, a production smart-home account, or an official vehicle-manufacturer API. Please reproduce security reports with the built-in simulator, Stub adapters, and test data only.

## Supported version

Security fixes are applied to the latest commit on the `main` branch. Older snapshots and exported delivery packages are not maintained separately.

## Reporting a vulnerability

Use GitHub's **Security** tab and choose **Report a vulnerability** when private vulnerability reporting is available.

If that option is unavailable, open a minimal public issue asking the maintainer to establish a private reporting channel. Do not include credentials, personal data, exploit payloads, or step-by-step reproduction details in a public issue.

High-value reports include:

- authentication or authorization bypasses;
- cross-user access to conversations, Agent tasks, workflows, trips, preferences, or SSE streams;
- bypasses of the MCP token or the server-side Policy Gate;
- ways to make a model or tool execute an unapproved write action;
- secret exposure, unsafe logging, or sensitive-data leakage;
- request replay, idempotency, or race-condition issues that create unintended side effects.

Please include the affected commit, impact, prerequisites, a minimal safe reproduction, and a suggested remediation when possible.

## Safe testing rules

- Use only accounts, tokens, and infrastructure you control.
- Do not target real vehicles, real homes, public services, or third-party accounts.
- Do not publish working secrets or weaponized exploit details.
- Stop testing if it could affect data or systems outside the local RoadMind environment.

## Security boundaries

The `demo` Spring profile provides loopback-only convenience authentication and is not production authentication. Redis is a cache and recovery projection; MySQL is the durable source of truth. The MCP service is a narrow read-only bridge and must not be exposed publicly without production-grade authentication, transport security, secret management, and network controls.
