#!/usr/bin/env sh
# Executes the real database scenarios with concise visible outcomes in Surefire reports.
set -eu
cd "$(dirname "$0")/../backend"
./mvnw '-Dtest=CoreIntegrationTest#workerCrashAfterProviderAcceptAvoidsDuplicateCall+providerOutageProtectsExistingAndStopsNew+agentCollapseImmediatelyRestrictsNewCalls+duplicateAnswersAreIdempotent+outOfOrderCannotReopenTerminal' test
cat target/surefire-reports/com.credresolve.smartdialer.CoreIntegrationTest.txt
