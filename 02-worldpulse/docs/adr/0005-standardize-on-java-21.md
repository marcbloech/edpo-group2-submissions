# ADR 0005: Standardize Runtime on Java 21

- Status: Accepted
- Date: 2026-03-17

## Context
Build and runtime mismatch caused failures when module targets differed from available JDK.
Local development and container builds must behave consistently across all services.

## Decision Drivers
- Remove compiler/runtime mismatch errors
- Keep one version policy across modules
- Align local and Docker execution

## Decision
Standardize all services and process module on Java 21.
Use Java 21 base images in Docker and Java 21 compiler release in Maven modules.

## Project Implementation
- Maven compiler release is set to 21 for:
	- signup
	- payment
	- notification
	- process
- Docker images use Eclipse Temurin 21 JDK as runtime base.
- Compose build/rebuild workflow relies on these module artifacts being packaged with Java 21 before container build.

## Alternatives Considered
- Keep mixed Java versions per module
- Downgrade all modules to Java 17

These options were rejected due to compatibility drift and user requirement to run on Java 21.

## Consequences
- Consistent local and container builds.
- Fewer environment-specific failures.
- Predictable CI/CD and deployment behavior.
- Future library upgrades should be validated against Java 21 as baseline.
- Team setup instructions can now assume one JDK target instead of per-module exceptions.
