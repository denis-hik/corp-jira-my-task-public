package local.corp.jira;

import com.google.gson.*;
import java.util.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import static local.corp.jira.JiraApi.*;

/** Read-only Development data; credentials never leave the configured Jira origin. */
final class JiraDevelopment {
  record Entry(String kind,String repository,String title,String description,String url,String repositoryUrl) {
    Entry(String kind,String repository,String title,String description,String url){this(kind,repository,title,description,url,"");}
  }
  record Data(List<Entry> entries,List<String> errors) {}
  static Data load(JiraApi api,String key)throws Exception{
    String id=str(api.get(issuePath(key)+"?fields=summary"),"id");
    if(!id.matches("[0-9]+"))throw new IllegalStateException("Missing Jira issue ID");
    JsonObject summary=api.development("summary?issueId="+id);
    List<Entry> entries=new ArrayList<>();List<String> errors=new ArrayList<>();collectErrors(summary,errors);
    JsonObject groups=obj(summary,"summary");Set<String> requests=new LinkedHashSet<>();
    for(String type:List.of("repository","branch","pullrequest")){
      for(String provider:obj(obj(groups,type),"byInstanceType").keySet())requests.add(URLEncoder.encode(provider,StandardCharsets.UTF_8)+"&dataType="+(type.equals("branch")?"pullrequest":type));
    }
    for(String request:requests){
      if(Thread.currentThread().isInterrupted())throw new InterruptedException();
      try{parse(api.development("detail?issueId="+id+"&applicationType="+request),entries,errors);}
      catch(InterruptedException e){throw e;}catch(Exception e){errors.add(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
    }
    return new Data(List.copyOf(new LinkedHashSet<>(entries)),List.copyOf(new LinkedHashSet<>(errors)));
  }
  static void collectErrors(JsonObject data,List<String> errors){
    JsonElement value=data.get("errors");
    if(value!=null&&!value.isJsonNull()&&!value.toString().equals("[]")&&!value.toString().equals("{}"))errors.add(I18n.t("Jira не удалось получить часть данных Development.")+" "+value.toString());
  }
  static void parse(JsonObject data,List<Entry> entries,List<String> errors){
    collectErrors(data,errors);
    for(JsonElement item:arr(data,"detail")){
      if(!item.isJsonObject())continue;JsonObject d=item.getAsJsonObject();
      for(JsonElement element:arr(d,"repositories")){
        JsonObject repo=element.getAsJsonObject();String name=str(repo,"name");
        for(JsonElement c:arr(repo,"commits")){
          JsonObject commit=c.getAsJsonObject();String hash=str(commit,"displayId");if(hash.isBlank())hash=str(commit,"id");
          entries.add(new Entry("Коммиты",name,hash+"  "+str(commit,"message"),join(str(obj(commit,"author"),"name"),date(str(commit,"authorTimestamp")),str(commit,"fileCount").isBlank()?"":I18n.t("Файлов: ")+str(commit,"fileCount")),str(commit,"url"),str(repo,"url")));
        }
        branches(arr(repo,"branches"),name,str(repo,"url"),entries);
      }
      branches(arr(d,"branches"),"","",entries);
      for(JsonElement p:arr(d,"pullRequests")){
        JsonObject pr=p.getAsJsonObject(),source=obj(pr,"source"),destination=obj(pr,"destination");
        entries.add(new Entry("Pull requests",str(obj(source,"repository"),"name"),str(pr,"id")+"  "+str(pr,"name"),join(str(pr,"status"),str(source,"branch")+" → "+str(destination,"branch"),str(obj(pr,"author"),"name"),date(str(pr,"lastUpdate"))),str(pr,"url"),str(obj(destination,"repository"),"url")));
      }
    }
  }
  static void branches(JsonArray array,String repository,String repositoryUrl,List<Entry> entries){for(JsonElement b:array){JsonObject branch=b.getAsJsonObject();String repo=str(obj(branch,"repository"),"name");entries.add(new Entry("Ветки",repo.isBlank()?repository:repo,str(branch,"name"),"",str(branch,"url"),str(obj(branch,"repository"),"url").isBlank()?repositoryUrl:str(obj(branch,"repository"),"url")));}}
  static String date(String value){try{if(value.matches("[0-9]+")){long timestamp=Long.parseLong(value);return java.time.Instant.ofEpochMilli(timestamp<100000000000L?timestamp*1000:timestamp).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));}}catch(RuntimeException ignored){}return value;}
  static String join(String... values){return String.join(" · ",Arrays.stream(values).filter(s->!s.isBlank()).toList());}
}
