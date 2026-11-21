package com.termpilot.eval.benchmark;

import com.termpilot.llm.LlmClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class UsageCalibrationTest {
    @TempDir Path temp;
    private UsageCalibration.Request request() {
        return new UsageCalibration.Request(1,"E3","a".repeat(64),"b".repeat(64),"2026-10-10",
                List.of(LlmClient.Message.system("fixed"),LlmClient.Message.user("frozen long workload")),List.of());
    }
    private UsageCalibration.Observation observation(String provider,int input,boolean present,String resolved) throws Exception {
        String model=provider.equals("deepseek") ? UsageCalibration.MODELS.get(0) : UsageCalibration.MODELS.get(1);
        return new UsageCalibration.Observation(provider,model,resolved,UsageCalibration.requestDigest(request()),input,12,0,present,1000000,16384,"c".repeat(64));
    }
    private UsageCalibration.Proof proof(String origin,List<UsageCalibration.Observation> observations) throws Exception {
        var r=request(); return new UsageCalibration.Proof(1,origin,r.sourceSha256(),r.candidateJarSha256(),UsageCalibration.requestDigest(r),r.runtimeDate(),observations);
    }
    @Test void symmetricProviderUsageAtBothIntervalBoundariesIsAccepted() throws Exception {
        for (int size : List.of(60000,100000)) UsageCalibration.validate(proof("REAL_PROVIDER_CALIBRATION",List.of(
                observation("deepseek",size,true,UsageCalibration.MODELS.get(0)),observation("glm",size,true,UsageCalibration.MODELS.get(1)))),request());
    }
    @Test void offlineEstimatesMissingModelsAndChangedInputsCannotGrantCalibration() throws Exception {
        var observations=List.of(observation("deepseek",75000,true,UsageCalibration.MODELS.get(0)),observation("glm",75000,true,UsageCalibration.MODELS.get(1)));
        assertThrows(IOException.class,()->UsageCalibration.validate(proof("OFFLINE_SCRIPT_CONTROL",observations),request()));
        assertThrows(IOException.class,()->UsageCalibration.validate(proof("REAL_PROVIDER_CALIBRATION",observations.subList(0,1)),request()));
        var changed=new UsageCalibration.Request(1,"E3","a".repeat(64),"b".repeat(64),"2026-10-10",List.of(LlmClient.Message.system("changed")),List.of());
        assertThrows(IOException.class,()->UsageCalibration.validate(proof("REAL_PROVIDER_CALIBRATION",observations),changed));
    }
    @Test void outsideRangeAbsentUsageWrongModelAndDuplicateProviderInvalidateProof() throws Exception {
        var good=observation("glm",75000,true,UsageCalibration.MODELS.get(1));
        for (var bad : List.of(observation("deepseek",59999,true,UsageCalibration.MODELS.get(0)),observation("deepseek",100001,true,UsageCalibration.MODELS.get(0)),
                observation("deepseek",75000,false,UsageCalibration.MODELS.get(0)),observation("deepseek",75000,true,"deepseek-v4-flash")))
            assertThrows(IOException.class,()->UsageCalibration.validate(proof("REAL_PROVIDER_CALIBRATION",List.of(bad,good)),request()));
        assertThrows(IOException.class,()->UsageCalibration.validate(proof("REAL_PROVIDER_CALIBRATION",List.of(good,good)),request()));
    }
    @Test void failedProviderIsCalledExactlyOnceAndLeavesTraceWithoutProof() throws Exception {
        int[] count={0}; var client=new LlmClient(){
            public String getModelName(){return "deepseek-flash";} public String getProviderName(){return "deepseek";} public int maxContextWindow(){return 1000000;}
            public ChatResponse chat(List<Message> m,List<Tool> t) throws IOException { count[0]++;throw new IOException("script failure"); }
            public ChatResponse chat(List<Message> m,List<Tool> t,StreamListener l) throws IOException{return chat(m,t);}
        };
        assertThrows(IOException.class,()->UsageCalibration.measure(request(),client,temp.resolve("trace.jsonl")));assertEquals(1,count[0]);
        assertEquals(1,Files.readAllLines(temp.resolve("trace.jsonl")).size());assertFalse(Files.exists(temp.resolve("proof.json")));
    }
    @Test void sampledUsageComesFromProviderResponseAndPreservesCachedTokenSubset() throws Exception {
        var client=new LlmClient(){
            public String getModelName(){return "deepseek-flash";} public String getProviderName(){return "deepseek";} public int maxContextWindow(){return 1000000;}
            public ChatResponse chat(List<Message> m,List<Tool> t){return new ChatResponse("assistant","收到",null,List.of(),75000,12,1200,getModelName(),true);}
            public ChatResponse chat(List<Message> m,List<Tool> t,StreamListener l){return chat(m,t);}
        };
        var observation=UsageCalibration.measure(request(),client,temp.resolve("trace.jsonl"));
        assertEquals(75000,observation.inputTokens());assertEquals(1200,observation.cachedInputTokens());assertTrue(observation.usagePresent());
    }
}
