package toolcallfaults.runner.cell;
public record Cell(String target,String version,String module,String surface,String fixtureId,String tool,int jdk,String httpClient) {
    public String key(){return target+"@"+version+"|"+module+"|"+surface+"|"+fixtureId+"|"+tool+"|jdk"+jdk+"|"+httpClient;}
}
