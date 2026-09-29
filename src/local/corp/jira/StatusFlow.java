package local.corp.jira;
import com.google.gson.*;
import java.util.*;
import java.util.function.*;
import static local.corp.jira.JiraApi.*;

final class StatusFlow {
  static final String TEXT="комментарии";
  static final Map<String,List<String>> EDGES=Map.ofEntries(
    Map.entry("OPEN",List.of("ANALYSIS","READY TO DEVELOP")),Map.entry("ANALYSIS",List.of("OPEN","NEED INFO","READY TO DEVELOP","DEVELOPMENT")),
    Map.entry("NEED INFO",List.of("ANALYSIS","DEVELOPMENT")),Map.entry("READY TO DEVELOP",List.of("ANALYSIS","DEVELOPMENT")),
    Map.entry("DEVELOPMENT",List.of("REVIEW","READY TO DEVELOP","NEED INFO","ANALYSIS")),Map.entry("REVIEW",List.of("DEVELOPMENT","READY TO TEST")),
    Map.entry("READY TO TEST",List.of("TESTING","READY TO DEVELOP")),Map.entry("TESTING",List.of("READY TO TEST","TESTING FAILED","READY TO MERGE","RESOLVED")),
    Map.entry("TESTING FAILED",List.of("READY TO TEST","READY TO DEVELOP")),Map.entry("READY TO MERGE",List.of("READY TO DEVELOP","RESOLVED")),
    Map.entry("RESOLVED",List.of("OPEN")),Map.entry("REJECTED",List.of("OPEN")));
  static String norm(String s){return s.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+"," ");}
  static List<Status> route(Status start,Status target,List<Transition> available,List<Status> catalog){
    if(start.id().equals(target.id()))return List.of(start);
    for(Transition t:available)if(t.to().id().equals(target.id()))return List.of(start,t.to());
    Map<String,Status> names=new HashMap<>();for(Status s:catalog){String n=norm(s.name());if(names.containsKey(n))names.put(n,null);else names.put(n,s);}
    ArrayDeque<List<Status>> queue=new ArrayDeque<>();Set<String> seen=new HashSet<>();seen.add(start.id());
    for(Transition t:available)if(seen.add(t.to().id()))queue.add(List.of(start,t.to()));
    while(!queue.isEmpty()){
      List<Status> path=queue.remove();Status last=path.get(path.size()-1);if(Set.of("RESOLVED","REJECTED").contains(norm(last.name())))continue;
      for(String name:EDGES.getOrDefault(norm(last.name()),List.of())){Status next=names.get(name);if(next==null||!seen.add(next.id()))continue;List<Status> candidate=new ArrayList<>(path);candidate.add(next);if(next.id().equals(target.id()))return candidate;queue.add(candidate);}
    }return null;
  }
  record Choice(Status status,List<Status> route,boolean direct,int order){
    String hint(){return direct?I18n.t("Прямой переход"):route==null?I18n.t("Маршрут неизвестен"):I18n.t("Через ")+(route.size()-2)+I18n.t(" промежуточных");}
  }
  static List<Choice> choices(Status start,List<Transition> transitions,List<Status> catalog){
    Map<String,Status> distinct=new LinkedHashMap<>();catalog.forEach(s->distinct.put(s.id(),s));transitions.forEach(t->distinct.put(t.to().id(),t.to()));
    Map<String,Integer> direct=new HashMap<>();for(int i=0;i<transitions.size();i++)direct.putIfAbsent(transitions.get(i).to().id(),i);
    List<Status> unique=new ArrayList<>(distinct.values());List<Choice> result=new ArrayList<>();
    for(Status s:unique)if(!s.id().equals(start.id()))result.add(new Choice(s,route(start,s,transitions,unique),direct.containsKey(s.id()),direct.getOrDefault(s.id(),Integer.MAX_VALUE)));
    result.sort(Comparator.comparingInt((Choice c)->c.direct()?0:1).thenComparingInt(c->c.direct()?c.order():c.route()==null?Integer.MAX_VALUE:c.route().size()).thenComparing(c->c.status().name(),String.CASE_INSENSITIVE_ORDER));return result;
  }
  interface Gateway {
    Status current()throws Exception;List<Transition> transitions()throws Exception;
    void assign(Status s)throws Exception;void transition(String id,JsonObject fields)throws Exception;
    JsonObject editMeta()throws Exception;void edit(JsonObject fields)throws Exception;
  }
  static Gateway gateway(JiraApi api,String key){return new Gateway(){
    public Status current()throws Exception{return api.current(key);}public List<Transition> transitions()throws Exception{return api.transitions(key);}
    public void assign(Status s)throws Exception{api.assign(key,s);}public void transition(String id,JsonObject f)throws Exception{api.transition(key,id,f);}
    public JsonObject editMeta()throws Exception{return api.editMeta(key);}public void edit(JsonObject f)throws Exception{api.edit(key,f);}
  };}
  static void check(Gateway api,Status expected,BooleanSupplier cancelled)throws Exception{
    if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new Exception(I18n.t("Остановлено. Выполненные изменения сохраняются."));
    if(!api.current().id().equals(expected.id()))throw new Exception(I18n.t("Статус задачи изменился. Автоматическое выполнение остановлено."));
  }
  static boolean textField(String id,JsonObject field){
    JsonObject schema=obj(field,"schema");String custom=str(schema,"custom");JsonArray operations=arr(field,"operations");
    boolean set=operations.isEmpty();for(JsonElement e:operations)if(e.getAsString().equals("set"))set=true;
    return !id.equals("comment")&&str(schema,"type").equals("string")&&(custom.isEmpty()||custom.endsWith(":textfield")||custom.endsWith(":textarea"))&&arr(field,"allowedValues").isEmpty()&&set;
  }
  static JsonObject required(JsonObject metadata)throws Exception{return required(metadata,TEXT);}
  static JsonObject required(JsonObject metadata,String fill)throws Exception{
    JsonObject values=new JsonObject();for(var entry:metadata.entrySet()){JsonObject f=entry.getValue().getAsJsonObject();if(f.has("required")&&f.get("required").getAsBoolean()){
      if(!textField(entry.getKey(),f))throw new Exception(I18n.t("Заполните поле «")+str(f,"name")+I18n.t("» вручную в Jira."));values.addProperty(entry.getKey(),fill);
    }}return values;
  }
  static JsonObject repair(ApiException error,JsonObject metadata){return repair(error,metadata,TEXT);}
  static JsonObject repair(ApiException error,JsonObject metadata,String fill){
    JsonObject values=new JsonObject();StringBuilder text=new StringBuilder();for(JsonElement e:arr(error.data,"errorMessages"))text.append(e.getAsString()).append(' ');String lower=text.toString().toLowerCase(Locale.ROOT);
    for(var entry:metadata.entrySet()){
      JsonObject f=entry.getValue().getAsJsonObject();String name=str(f,"name").toLowerCase(Locale.ROOT),message=str(obj(error.data,"errors"),entry.getKey());
      if(!name.isBlank()&&(lower.contains("'"+name+"'")||lower.contains("«"+name+"»")||lower.contains("\""+name+"\"")))message+=" "+lower;
      if(message.toLowerCase(Locale.ROOT).matches("(?s).*(по умолчанию|default|required|обязател|заполн|не может быть пуст).*")&&textField(entry.getKey(),f))values.addProperty(entry.getKey(),fill);
    }return values;
  }
  static void execute(Gateway api,Status start,Choice choice,BooleanSupplier cancelled,Consumer<String> progress)throws Exception{execute(api,start,choice,cancelled,progress,TEXT);}
  static void execute(Gateway api,Status start,Choice choice,BooleanSupplier cancelled,Consumer<String> progress,String fill)throws Exception{
    if(fill==null||fill.isBlank())throw new IllegalArgumentException(I18n.t("Укажите текст для обязательных полей."));
    check(api,start,cancelled);progress.accept(I18n.t("Прямая попытка → ")+choice.status().name());ApiException rejection=null;
    try{api.assign(choice.status());}catch(ApiException e){if(!Set.of(400,403,405,422).contains(e.status))throw e;rejection=e;}
    Status actual=api.current();if(actual.id().equals(choice.status().id())){progress.accept(I18n.t("Готово: ")+actual.name());return;}
    if(!actual.id().equals(start.id()))throw new Exception(I18n.t("После прямой попытки статус изменился неожиданно. Обновите задачу."));
    if(rejection==null)throw new Exception(I18n.t("Jira приняла запрос, но не подтвердила целевой статус. Автопродолжения нет."));
    List<Status> route=choice.route();if(route==null)throw new Exception(I18n.t("Прямое назначение отклонено. Резервный маршрут неизвестен."));
    progress.accept(I18n.t("Прямое назначение отклонено. Проходим маршрут…"));
    for(int i=1;i<route.size();i++){
      Status previous=route.get(i-1),next=route.get(i);check(api,previous,cancelled);
      List<Transition> matches=api.transitions().stream().filter(t->t.to().id().equals(next.id())).toList();
      if(matches.size()!=1)throw new Exception(matches.isEmpty()?I18n.t("Следующий переход недоступен: ")+next.name():I18n.t("Несколько действий ведут в ")+next.name()+I18n.t(". Выберите действие вручную в Jira."));
      Transition step=matches.get(0);JsonObject fields=required(step.fields(),fill);Set<String> repaired=new HashSet<>();
      for(int attempt=0;;attempt++){
        check(api,previous,cancelled);
        try{api.transition(step.id(),fields);break;}catch(ApiException error){
          if(error.status!=400||attempt>=3)throw error;check(api,previous,cancelled);
          String id=step.id();List<Transition> fresh=api.transitions().stream().filter(t->t.id().equals(id)&&t.to().id().equals(next.id())).toList();if(fresh.size()!=1)throw new Exception(I18n.t("Переход больше недоступен."));step=fresh.get(0);
          JsonObject patch=repair(error,step.fields(),fill),edit=new JsonObject();if(patch.size()==0)edit=repair(error,api.editMeta(),fill);
          Set<String> ids=new HashSet<>(patch.keySet());ids.addAll(edit.keySet());if(ids.isEmpty()||ids.stream().anyMatch(k->repaired.contains(k)||fields.has(k)))throw error;
          check(api,previous,cancelled);if(edit.size()>0)api.edit(edit);for(var e:patch.entrySet())fields.add(e.getKey(),e.getValue());repaired.addAll(ids);progress.accept(I18n.t("Обязательное текстовое поле заполнено."));
        }
      }
      actual=api.current();if(!actual.id().equals(next.id()))throw new Exception(I18n.t("Jira не подтвердила ожидаемый статус. Выполнение остановлено."));
      progress.accept(I18n.t("Шаг ")+i+"/"+(route.size()-1)+": "+actual.name());
    }
    if(!api.current().id().equals(choice.status().id()))throw new Exception(I18n.t("Итоговый статус не совпадает с выбранным."));
  }
}
