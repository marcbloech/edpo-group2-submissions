# ADR 0005: Standardize on Java 21

## Status

Accepted

## Context

We had mismatches between module compiler targets and the available JDK, which caused build failures. Local dev and Docker builds need to use the same version.

## Decision

All services use Java 21. Maven compiler release is 21 in every module. Docker images use Eclipse Temurin 21 JDK.

## Consequences

Builds are consistent between local and Docker. One JDK version in the setup instructions. Library upgrades need to be checked against 21.
