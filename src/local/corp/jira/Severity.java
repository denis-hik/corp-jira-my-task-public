package local.corp.jira;

import com.google.gson.*;
import com.intellij.ui.JBColor;
import java.awt.Color;
import java.util.*;
import static local.corp.jira.JiraApi.*;

final class Severity {
  record Level(String id,String name){public String toString(){return name;}}
  record Data(String field,Level current,List<Level> options){}
  static boolean named(String name){return Set.of("severity","серьезность","серьёзность","критичность").contains(name.trim().toLowerCase(Locale.ROOT));}
  static Level level(JsonElement value){if(value==null||value.isJsonNull())return new Level("",I18n.t("Не указана"));if(!value.isJsonObject())return new Level("",value.isJsonPrimitive()?value.getAsString():value.toString());JsonObject o=value.getAsJsonObject();String name=str(o,"value");if(name.isBlank())name=str(o,"name");return new Level(str(o,"id"),name);}
  record FilterOptions(String field,List<Level> levels){}
  static FilterOptions filters(JiraApi api)throws Exception{
    String field=null;
    for(JsonElement entry:api.request("GET","/field",null).getAsJsonArray()){
      JsonObject f=entry.getAsJsonObject();if(!named(str(f,"name")))continue;
      if(field!=null)throw new IllegalStateException(I18n.t("В Jira несколько полей Severity. Требуется уточнить поле."));field=str(f,"id");
    }
    if(field==null||!field.matches("customfield_[0-9]+"))throw new IllegalStateException(I18n.t("Поле Severity не найдено."));
    String jqlField="cf["+field.substring(12)+"]";
    JsonObject suggestions=api.get("/jql/autocompletedata/suggestions?fieldName="+java.net.URLEncoder.encode(jqlField,java.nio.charset.StandardCharsets.UTF_8));
    Map<String,Level> levels=new LinkedHashMap<>();
    for(JsonElement entry:arr(suggestions,"results")){
      String value=str(entry.getAsJsonObject(),"value");
      if(value.startsWith("\"")&&value.endsWith("\"")&&value.length()>1)value=value.substring(1,value.length()-1).replace("\\\"","\"").replace("\\\\","\\");
      if(!value.isBlank())levels.put(value,new Level(value,value));
    }
    return new FilterOptions(jqlField,List.copyOf(levels.values()));
  }
  static String clause(String field,Level level){
    if(!field.matches("cf\\[[0-9]+\\]"))throw new IllegalArgumentException("Invalid severity field");
    return field+" = \""+level.name().replace("\\","\\\\").replace("\"","\\\"")+"\"";
  }
  static Data load(JiraApi api,String key)throws Exception{
    String field=null;
    for(JsonElement entry:api.request("GET","/field",null).getAsJsonArray()){
      JsonObject f=entry.getAsJsonObject();if(!named(str(f,"name")))continue;
      if(field!=null)throw new IllegalStateException(I18n.t("В Jira несколько полей Severity. Требуется уточнить поле."));field=str(f,"id");
    }
    if(field==null)return null;
    if(!field.matches("[a-zA-Z0-9_]+"))throw new IllegalStateException("Invalid field ID");
    Level current=level(obj(api.get(JiraApi.issuePath(key)+"?fields="+field),"fields").get(field));
    List<Level> options=new ArrayList<>();JsonObject meta;
    try{meta=obj(api.editMeta(key),field);}catch(ApiException e){if(e.status==403)return new Data(field,current,List.of());throw e;}
    boolean set=false;for(JsonElement op:arr(meta,"operations"))if(op.isJsonPrimitive()&&"set".equals(op.getAsString()))set=true;
    if(set&&"option".equals(str(obj(meta,"schema"),"type")))for(JsonElement value:arr(meta,"allowedValues")){
      if(value.isJsonObject()&&value.getAsJsonObject().has("disabled")&&value.getAsJsonObject().get("disabled").getAsBoolean())continue;
      Level option=level(value);if(!option.id().isBlank()&&!option.name().isBlank())options.add(option);
    }
    return new Data(field,current,List.copyOf(options));
  }
  static void save(JiraApi api,String key,Data data,Level selected)throws Exception{
    Data fresh=load(api,key);if(fresh==null||!fresh.field().equals(data.field())||fresh.options().stream().noneMatch(v->v.id().equals(selected.id())))throw new IllegalStateException(I18n.t("Уровень Severity больше недоступен. Обновите карточку."));
    JsonObject value=new JsonObject();value.addProperty("id",selected.id());JsonObject fields=new JsonObject();fields.add(data.field(),value);api.edit(key,fields);
    Level actual=level(obj(api.get(JiraApi.issuePath(key)+"?fields="+data.field()),"fields").get(data.field()));
    if(!actual.id().equals(selected.id()))throw new IllegalStateException(I18n.t("Jira не подтвердила Severity. Обновите карточку."));
  }
  static Color color(Level level){String n=level.name().toLowerCase(Locale.ROOT);int light,dark;
    if(n.matches(".*(block|critical|крит|блок|fatal|severe).*")){light=0xB42318;dark=0xFF827C;}
    else if(n.matches(".*(major|high|высок|значит).*")){light=0xA54800;dark=0xFFAB66;}
    else if(n.matches(".*(medium|moderate|normal|средн|умерен).*")){light=0x805F00;dark=0xEAC85E;}
    else if(n.matches(".*(minor|low|низк|незнач).*")){light=0x216E4E;dark=0x7EE2B8;}
    else {int[] l={0x2459AA,0x6941A5,0x006B78};int[] d={0x85B8FF,0xC7A6FF,0x79DCE8};int index=Math.floorMod(level.name().hashCode(),l.length);light=l[index];dark=d[index];}
    return new JBColor(new Color(light),new Color(dark));
  }
}
