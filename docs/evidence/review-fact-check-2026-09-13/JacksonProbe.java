import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
public class JacksonProbe {
  public record Event(long walletSequence, long balanceAfter) {}
  public static void main(String[] args) throws Exception {
    var m=JsonMapper.builder().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    System.out.println("Jackson="+m.version()+", Java="+System.getProperty("java.version"));
    for (var raw : new String[]{"18446744073709551621","9223372036854775808","18446744073709551617","1.5"}) {
      var n=m.readTree(raw);
      System.out.println(raw+" integral="+n.isIntegralNumber()+" fitsLong="+n.canConvertToLong()+" longValue="+n.longValue());
    }
    for(var raw : new String[]{"{\"walletSequence\":1,\"balanceAfter\":18446744073709551621}", "{\"walletSequence\":1}", "{\"walletSequence\":1,\"balanceAfter\":null}", "{\"walletSequence\":1,\"balanceAfter\":1.5}"}) {
      try {System.out.println(raw+" -> "+m.readValue(raw,Event.class));}
      catch(Exception e) {System.out.println(raw+" -> "+e.getClass().getSimpleName());}
    }
  }
}