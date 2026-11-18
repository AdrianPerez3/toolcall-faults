package toolcallfaults.kit;
import java.util.*;
import java.util.concurrent.*;
public final class Signals {
    private final List<String> signals=new ArrayList<>();
    private final CountDownLatch first=new CountDownLatch(1);
    private long firstNanos;
    private Throwable error;
    public synchronized void complete(){signal("complete");}
    public synchronized void error(Throwable t){if(error==null)error=t;signal("error");}
    private void signal(String name){if(signals.isEmpty())firstNanos=System.nanoTime();signals.add(name);first.countDown();}
    public boolean awaitFirst(long millis){try{return first.await(millis,TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}}
    public void linger(){long start;synchronized(this){start=firstNanos;}if(start==0)return;long left=1_000_000_000L-(System.nanoTime()-start);
        if(left>0)try{TimeUnit.NANOSECONDS.sleep(left);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    public synchronized List<String> list(){return List.copyOf(signals);}
    public static String cut(String s){if(s==null)return null;int end=Math.min(500,s.length());if(end<s.length()&&end>0&&Character.isHighSurrogate(s.charAt(end-1)))end--;return s.substring(0,end);}
    public synchronized List<Map<String,Object>> errorChain(){
        List<Map<String,Object>> chain=new ArrayList<>();Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable t=error;t!=null&&chain.size()<5&&seen.add(t);t=t.getCause()){
            Map<String,Object> item=new LinkedHashMap<>();item.put("type",t.getClass().getName());item.put("message",cut(t.getMessage()));chain.add(item);
        }return chain;
    }
}
