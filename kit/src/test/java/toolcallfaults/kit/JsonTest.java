package toolcallfaults.kit;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class JsonTest {
    @Test void writes_nulls_and_order(){var m=new LinkedHashMap<String,Object>();for(String k:List.of("priority","title","body"))m.put(k,null);assertEquals("{\"priority\":null,\"title\":null,\"body\":null}",Json.write(m));}
    @Test void escapes_quotes_newlines_controls(){assertEquals("\"a\\\"b\\nc\\u0001\"",Json.write("a\"b\nc\u0001"));}
    @Test void validity_is_strict(){
        for(String s:List.of("{}","{} ","[1,true,null,\"é\"]","-2.3e+4"))assertTrue(Json.isValid(s),s);
        for(String s:List.of("","{\"a\":1","{}{\"a\":1}","[1,]","01","NaN","\"a\nb\"","{\"a\":}","true false", "\"\\u٠٠٠٠\""))assertFalse(Json.isValid(s),s);
    }
}
