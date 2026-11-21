package com.termpilot.eval.benchmark.relay;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ContinuationRelayProtocolTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    BenchmarkRelayProtocol.Header header(BenchmarkRelayProtocol.Direction direction,BenchmarkRelayProtocol.FrameType type,String id,long sequence){return new BenchmarkRelayProtocol.Header(direction,type,id,sequence);}
    BenchmarkRelayProtocol.SessionStart start(){return new BenchmarkRelayProtocol.SessionStart(header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,BenchmarkRelayProtocol.FrameType.SESSION_START,"",0),
            "episode-test","deepseek","deepseek-v4-flash",BenchmarkRelayProtocol.AgentMode.REACT,BenchmarkRelayProtocol.ToolProfile.FILE_ONLY,"frozen first delivery","2026-10-10","UTC",System.currentTimeMillis()+60000,
            new BenchmarkRelayProtocol.AgentLimits(1000000,48,8,1000000,16384),new BenchmarkRelayProtocol.Capabilities(true,true,true,false,false,"none",1000000),
            new BenchmarkRelayProtocol.Limits(BenchmarkFramedChannel.MAX_FRAME_BYTES,BenchmarkFramedChannel.MAX_SESSION_BYTES,512,128),List.of(),BenchmarkRelayProtocol.InteractionMode.NINE_TURN_CONTINUATION);}
    BenchmarkRelayProtocol.Validator ready(){var validator=new BenchmarkRelayProtocol.Validator();validator.accept(start());validator.accept(new BenchmarkRelayProtocol.WorkerReady(header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,BenchmarkRelayProtocol.FrameType.WORKER_READY,"",0),start().capabilities()));return validator;}
    @Test void exactlyNineOrderedDeliveriesAreRequiredAndCannotRestartAtTurnOne() throws Exception {
        var v=ready();
        assertThrows(IllegalStateException.class,()->v.accept(new BenchmarkRelayProtocol.TurnComplete(header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,BenchmarkRelayProtocol.FrameType.TURN_COMPLETE,"skip",0),2,"answer",List.of())));
        for(int turn=1;turn<=8;turn++){
            String id="turn-"+turn;v.accept(new BenchmarkRelayProtocol.TurnComplete(header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,BenchmarkRelayProtocol.FrameType.TURN_COMPLETE,id,0),turn,"answer",List.of()));
            if(turn==1)assertThrows(IllegalStateException.class,()->v.accept(new BenchmarkRelayProtocol.TurnContinue(header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,BenchmarkRelayProtocol.FrameType.TURN_CONTINUE,id,1),3,"skip")));
            v.accept(new BenchmarkRelayProtocol.TurnContinue(header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,BenchmarkRelayProtocol.FrameType.TURN_CONTINUE,id,1),turn+1,"frozen next",turn==8));
        }
        assertThrows(IllegalStateException.class,()->v.accept(new BenchmarkRelayProtocol.TurnComplete(header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,BenchmarkRelayProtocol.FrameType.TURN_COMPLETE,"extra",0),8,"answer",List.of())));
        v.accept(new BenchmarkRelayProtocol.WorkerComplete(header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,BenchmarkRelayProtocol.FrameType.WORKER_COMPLETE,"",0),"final",List.of()));
    }
    @Test void contextObservationNeedsMatchingAckBeforeChatOrContinuation() throws Exception {
        var v=ready();var data=JSON.createObjectNode();data.set("view",JSON.createArrayNode());data.put("ledger","native bytes");
        var event=new BenchmarkRelayProtocol.ContextEvent(header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,BenchmarkRelayProtocol.FrameType.CONTEXT_EVENT,"context-1",0),1,"PRE_COMPACTION",data);
        assertEquals(event,BenchmarkRelayProtocol.decode(BenchmarkRelayProtocol.encode(event)));v.accept(event);
        assertThrows(IllegalStateException.class,()->v.accept(new BenchmarkRelayProtocol.ContextEventAck(header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,BenchmarkRelayProtocol.FrameType.CONTEXT_EVENT_ACK,"wrong",1))));
        v.accept(new BenchmarkRelayProtocol.ContextEventAck(header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,BenchmarkRelayProtocol.FrameType.CONTEXT_EVENT_ACK,"context-1",1)));
        assertThrows(IllegalStateException.class,()->v.accept(event));
    }
    @Test void rejectsCoercedTurnAndCompactionFlagBeforeDeserialization() throws Exception {
        var frame=new BenchmarkRelayProtocol.TurnContinue(header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,BenchmarkRelayProtocol.FrameType.TURN_CONTINUE,"turn-8",1),9,"next",true);
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(BenchmarkRelayProtocol.encode(frame));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("payload")).put("compactBeforeTurn","true");
        assertThrows(java.io.IOException.class,()->BenchmarkRelayProtocol.decode(JSON.writeValueAsBytes(tree)));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("payload")).put("compactBeforeTurn",true).put("turn",9.0);
        assertThrows(java.io.IOException.class,()->BenchmarkRelayProtocol.decode(JSON.writeValueAsBytes(tree)));
    }
}
