package toolcallfaults.kit;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class SignalsTest {
    @Test void keeps_every_signal_in_order(){var s=new Signals();s.error(new RuntimeException("x"));s.complete();assertEquals(List.of("error","complete"),s.list());assertTrue(s.awaitFirst(1));}
    @Test void chain_lists_causes(){var s=new Signals();s.error(new RuntimeException("outer",new IllegalStateException("inner")));assertEquals("java.lang.RuntimeException",s.errorChain().get(0).get("type"));assertEquals("java.lang.IllegalStateException",s.errorChain().get(1).get("type"));}
    @Test void message_cut_keeps_surrogate_pairs(){var s=new Signals();s.error(new RuntimeException("a".repeat(499)+"😀b"));assertEquals("a".repeat(499),s.errorChain().get(0).get("message"));}
}
