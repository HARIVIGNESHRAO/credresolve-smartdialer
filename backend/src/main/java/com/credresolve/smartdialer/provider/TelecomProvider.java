package com.credresolve.smartdialer.provider;

import java.util.UUID;

public interface TelecomProvider {
  record CallRequest(UUID callId, String idempotencyKey) {}

  record CallInitiationResult(String providerCallId) {}

  record ProviderHealth(boolean healthy, int latencyMs, double failureRate, String circuit) {}

  record ProviderCallStatus(String providerCallId, String status) {}

  CallInitiationResult initiateCall(CallRequest request);

  ProviderHealth getHealth();

  ProviderCallStatus getCallStatus(String idempotencyKey);
}
