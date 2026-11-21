package com.termpilot.llm;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BenchmarkSingleAttemptTest {
    @Test void frozenBenchmarkClientsDisableAutomaticRetriesWithoutChangingInteractiveClients() {
        assertEquals(1,new DeepSeekClient("offline-key","deepseek-v4-flash",16384).retryPolicy().maxAttempts());
        assertEquals(1,new GLMClient("offline-key","glm-5.3-flash",16384).retryPolicy().maxAttempts());
        assertEquals(LlmRetryPolicy.fromSystemProperties().maxAttempts(),new DeepSeekClient("offline-key","deepseek-flash").retryPolicy().maxAttempts());
    }
}
