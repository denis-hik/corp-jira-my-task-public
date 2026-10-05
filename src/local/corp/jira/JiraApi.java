package local.corp.jira;

import com.google.gson.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public final class JiraApi implements AutoCloseable {
  @Override public void close(){client.shutdownNow();}
  public static final String ORIGIN="";
  public static final String JQL="assignee = currentUser() AND status NOT IN (Closed, Done, Resolved, Declined, Rejected)";
  private final String token;
  final String origin;
  volatile User self;
  private final HttpClient client;
  final JiraTls tls;
  JiraApi(String token){this(token,ORIGIN);}
  JiraApi(String token,String origin){this(token,origin,null);}
  JiraApi(String token,String origin,String acceptedFingerprint){this.origin=origin;this.token=token;try{tls=new JiraTls(URI.create(origin).getHost(),acceptedFingerprint);}catch(java.security.GeneralSecurityException e){throw new IllegalStateException(I18n.t("Не удалось подготовить TLS"),e);}client=HttpClient.newBuilder().sslContext(tls.context).connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();}
  public static final class ApiException extends IOException {
    final int status;final JsonObject data;
    ApiException(int status,JsonObject data){super(message(status,data));this.status=status;this.data=data;}
    private static String message(int code,JsonObject data){
      List<String> errors=new ArrayList<>();for(JsonElement e:arr(data,"errorMessages"))errors.add(e.getAsString());
      for(var e:obj(data,"errors").entrySet())errors.add(e.getKey()+": "+e.getValue().getAsString());
      return "Jira HTTP "+code+(errors.isEmpty()?"":": "+String.join("; ",errors))+(code==401?I18n.t(". Проверьте PAT."):code==403?I18n.t(". Недостаточно прав."):"");
    }
  }
  JsonElement request(String method,String path,JsonObject body)throws Exception{
    return restRequest(method,"/rest/api/2"+path,body);
  }
  JsonObject development(String query)throws Exception{
    if(!query.matches("(?:summary|detail)\\?issueId=[0-9]+(?:&applicationType=[A-Za-z0-9%_.+-]+&dataType=(?:repository|branch|pullrequest))?"))throw new IllegalArgumentException("Development query");
    return restRequest("GET","/rest/dev-status/1.0/issue/"+query,null).getAsJsonObject();
  }
  private JsonElement restRequest(String method,String path,JsonObject body)throws Exception{
    if(Thread.currentThread().isInterrupted())throw new InterruptedException(I18n.t("Отменено"));
    if(!path.startsWith("/")||path.startsWith("//"))throw new IllegalArgumentException("API path");
    HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(this.origin+path)).timeout(Duration.ofSeconds(25))
      .header("Authorization","Bearer "+token).header("Accept","application/json");
    if(body!=null)builder.header("Content-Type","application/json");
    builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toString(),StandardCharsets.UTF_8));
    HttpResponse<byte[]> response;
    try{response=client.send(builder.build(),LimitedBody.handler(16L*1048576));}
    catch(IOException e){if(tls.failure!=null)throw new IOException(I18n.t("Ошибка сертификата Jira: ")+tls.failure.reason(),e);throw new IOException(method.equals("GET")?I18n.t("Нет ответа Jira: ")+e.getClass().getSimpleName()+I18n.t(". Проверьте сеть и сертификат сервера."):I18n.t("Ответ на изменение не получен. Результат неизвестен: обновите задачу. Автоповтора нет."),e);}
    String responseText=new String(response.body(),StandardCharsets.UTF_8);
    JsonElement parsed=JsonNull.INSTANCE;
    if(!responseText.isBlank())try{parsed=JsonParser.parseString(responseText);}catch(JsonParseException ignored){}
    if(response.statusCode()<200||response.statusCode()>=300){
      if(response.statusCode()>=300&&response.statusCode()<400)throw new IOException(I18n.t("Jira перенаправляет запрос. Проверьте, что PAT разрешён для REST API."));
      throw new ApiException(response.statusCode(),parsed.isJsonObject()?parsed.getAsJsonObject():new JsonObject());
    }
    if(response.statusCode()!=204&&parsed.isJsonNull())throw new IOException(I18n.t("Ответ Jira не является JSON. Проверьте подключение по PAT."));
    return parsed;
  }
  JsonObject get(String path)throws Exception{JsonObject result=request("GET",path,null).getAsJsonObject();if(path.equals("/myself"))self=user(result);return result;}
  JsonArray paged(String path,String name)throws Exception{
    JsonArray all=new JsonArray();int start=0;long characters=0;
    for(int page=0;page<1000;page++){
      JsonObject result=get(path+(path.contains("?")?"&":"?")+"startAt="+start+"&maxResults=100");JsonArray rows=arr(result,name);characters+=rows.toString().length();if(characters>32L*1048576)throw new IOException(I18n.t("Слишком большой результат Jira. Уточните фильтр или откройте задачу в браузере."));all.addAll(rows);start+=rows.size();
      int total=result.has("total")?result.get("total").getAsInt():start;
      if(start>=total)return all;
      if(rows.isEmpty())throw new IOException(I18n.t("Jira вернула неполную страницу: обновите список."));
    }
    throw new IOException(I18n.t("Слишком большой результат Jira. Получен не весь список."));
  }
  java.awt.image.BufferedImage avatar()throws Exception{
    JsonObject urls=obj(get("/myself"),"avatarUrls");String url=str(urls,"48x48");if(url.isBlank())return null;
    URI uri=URI.create(this.origin).resolve(url),origin=URI.create(this.origin);
    if(!origin.getScheme().equals(uri.getScheme())||!origin.getHost().equalsIgnoreCase(uri.getHost())||origin.getPort()!=uri.getPort())return null;
    HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token).GET().build();
    HttpResponse<byte[]> response=client.send(request,LimitedBody.handler(1048576));
    if(response.statusCode()!=200)return null;return decodeImage(response.body(),48,48);
  }
  java.nio.file.Path exportMedia(Media media,java.nio.file.Path directory,int index)throws Exception{
    URI uri=URI.create(origin).resolve(media.url()),base=URI.create(origin);if(!Objects.equals(uri.getScheme(),base.getScheme())||!Objects.equals(uri.getHost(),base.getHost())||uri.getPort()!=base.getPort())throw new IOException(I18n.t("Медиа на другом сервере: ")+media.name());
    var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+token).GET().build();var response=client.send(request,LimitedBody.handler(20L*1048576));if(response.statusCode()!=200)throw new IOException(I18n.t("Медиа ")+media.name()+": HTTP "+response.statusCode());byte[] bytes=response.body();
    String filename=media.name().replaceAll("[^\\p{L}\\p{N}._-]","_");if(filename.isBlank())filename="image";if(filename.length()>160)filename=filename.substring(filename.length()-160);java.nio.file.Path path=directory.resolve(index+"-"+filename);java.nio.file.Files.write(path,bytes);return path;
  }
  java.awt.image.BufferedImage mediaImage(String url)throws Exception{return mediaImage(url,1600,1000);}
  java.awt.image.BufferedImage mediaImage(String url,int width,int height)throws Exception{
    URI uri=URI.create(this.origin).resolve(url),origin=URI.create(this.origin);
    if(!origin.getScheme().equals(uri.getScheme())||!origin.getHost().equalsIgnoreCase(uri.getHost())||origin.getPort()!=uri.getPort())throw new IOException(I18n.t("Изображение находится на другом сервере"));
    HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+token).GET().build();
    HttpResponse<byte[]> response=client.send(request,LimitedBody.handler(20L*1048576));
    if(response.statusCode()!=200)throw new IOException(I18n.t("Изображение: HTTP ")+response.statusCode());return decodeImage(response.body(),width,height);
  }
  static java.awt.image.BufferedImage decodeImage(byte[] bytes,int width,int height)throws IOException{
    try(var input=javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))){var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException(I18n.t("Формат изображения не поддерживается"));var reader=readers.next();try{reader.setInput(input);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<=0||h<=0)throw new IOException(I18n.t("Некорректное изображение"));var param=reader.getDefaultReadParam();int sample=Math.max(1,(int)Math.ceil(Math.max(w/(double)width,h/(double)height)));param.setSourceSubsampling(sample,sample,0,0);return reader.read(0,param);}finally{reader.dispose();}}
  }

  List<IssueRow> issues()throws Exception{return issues(JQL);}
  List<IssueRow> issues(String jql)throws Exception{
    String path="/search?jql="+URLEncoder.encode(jql,StandardCharsets.UTF_8)+"&fields=summary,status,priority,updated";
    List<IssueRow> rows=new ArrayList<>();Set<String> seen=new HashSet<>();
    for(JsonElement el:paged(path,"issues")){JsonObject i=el.getAsJsonObject(),f=obj(i,"fields");String key=str(i,"key");if(seen.add(key))rows.add(new IssueRow(key,str(f,"summary"),status(obj(f,"status")),str(obj(f,"priority"),"name")));}
    return rows;
  }
  IssueDetails details(String key)throws Exception{
    JsonObject issue=get(issuePath(key)+"?fields=summary,description,status,assignee,reporter,priority,updated,attachment");JsonObject fields=obj(issue,"fields");
    List<Comment> comments=new ArrayList<>();for(JsonElement el:paged(issuePath(key)+"/comment","comments")){JsonObject c=el.getAsJsonObject();comments.add(new Comment(str(obj(c,"author"),"displayName"),str(c,"created"),plain(c.get("body"))));}
    comments.sort(Comparator.comparing((Comment c)->commentTime(c.date())).reversed());
    List<Media> media=new ArrayList<>();for(JsonElement el:arr(fields,"attachment")){JsonObject a=el.getAsJsonObject();String mime=str(a,"mimeType");if(mime.startsWith("image/"))media.add(new Media(str(a,"filename"),str(a,"content"),str(a,"thumbnail")));}
    return new IssueDetails(key,str(fields,"summary"),status(obj(fields,"status")),plain(fields.get("description")),str(obj(fields,"assignee"),"displayName"),str(obj(fields,"reporter"),"displayName"),str(obj(fields,"priority"),"name"),comments,media,str(obj(fields,"assignee"),"name"));
  }
  Status current(String key)throws Exception{return status(obj(obj(get(issuePath(key)+"?fields=status"),"fields"),"status"));}
  List<Status> statuses()throws Exception{List<Status> rows=new ArrayList<>();for(JsonElement e:request("GET","/status",null).getAsJsonArray())rows.add(status(e.getAsJsonObject()));return rows;}
  List<Transition> transitions(String key)throws Exception{List<Transition> rows=new ArrayList<>();for(JsonElement e:arr(get(issuePath(key)+"/transitions?expand=transitions.fields"),"transitions")){JsonObject t=e.getAsJsonObject();rows.add(new Transition(str(t,"id"),str(t,"name"),status(obj(t,"to")),obj(t,"fields")));}return rows;}
  record User(String name,String displayName){@Override public String toString(){return displayName+" ("+name+")";}}
  static User user(JsonObject o){return new User(str(o,"name"),str(o,"displayName"));}
  List<User> assignable(String key,String query)throws Exception{
    String path="/user/assignable/search?issueKey="+URLEncoder.encode(key,StandardCharsets.UTF_8)+"&username="+URLEncoder.encode(query,StandardCharsets.UTF_8)+"&maxResults=50";
    List<User> result=new ArrayList<>();for(JsonElement e:request("GET",path,null).getAsJsonArray()){JsonObject o=e.getAsJsonObject();User u=user(o);if(!u.name().isBlank()&&(!o.has("active")||o.get("active").getAsBoolean()))result.add(u);}return result;
  }
  List<User> mentionUsers(String query)throws Exception{
    JsonObject result=get("/user/picker?query="+URLEncoder.encode(query,StandardCharsets.UTF_8)+"&maxResults=3&showAvatar=false");
    return mentionUsers(result);
  }
  static List<User> mentionUsers(JsonObject result){
    List<User> users=new ArrayList<>();for(JsonElement e:arr(result,"users")){User u=user(e.getAsJsonObject());if(!u.name().isBlank()&&users.stream().noneMatch(v->v.name().equals(u.name())))users.add(u);if(users.size()==3)break;}return users;
  }
  void assignUser(String key,User user)throws Exception{if(user.name().isBlank())throw new IllegalArgumentException(I18n.t("Неизвестный логин исполнителя"));JsonObject body=new JsonObject();body.addProperty("name",user.name());request("PUT",issuePath(key)+"/assignee",body);}
  void comment(String key,String text)throws Exception{JsonObject body=new JsonObject();body.addProperty("body",text);request("POST",issuePath(key)+"/comment",body);}
  String upload(String key,java.nio.file.Path file)throws Exception{
    String boundary="Corp"+UUID.randomUUID().toString().replace("-","");String name=file.getFileName().toString().replace("\\","_").replace("\"","_").replace("\r","_").replace("\n","_");
    String head="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: application/octet-stream\r\n\r\n";
    var body=HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(head,StandardCharsets.UTF_8),HttpRequest.BodyPublishers.ofFile(file),HttpRequest.BodyPublishers.ofString("\r\n--"+boundary+"--\r\n"));
    var request=HttpRequest.newBuilder(URI.create(this.origin+"/rest/api/2"+issuePath(key)+"/attachments")).timeout(Duration.ofMinutes(2)).header("Authorization","Bearer "+token).header("X-Atlassian-Token","no-check").header("Content-Type","multipart/form-data; boundary="+boundary).header("Accept","application/json").POST(body).build();
    HttpResponse<byte[]> response;try{response=client.send(request,LimitedBody.handler(16L*1048576));}catch(IOException e){throw new IOException(I18n.t("Результат загрузки неизвестен. Проверьте вложения в Jira перед повторной отправкой."),e);}
    String responseText=new String(response.body(),StandardCharsets.UTF_8);
    JsonElement parsed=JsonNull.INSTANCE;try{parsed=JsonParser.parseString(responseText);}catch(Exception ignored){}
    if(response.statusCode()<200||response.statusCode()>=300)throw new ApiException(response.statusCode(),parsed.isJsonObject()?parsed.getAsJsonObject():new JsonObject());
    if(!parsed.isJsonArray()||parsed.getAsJsonArray().isEmpty())throw new IOException(I18n.t("Jira не подтвердила загрузку файла. Проверьте вложения перед повтором."));
    JsonObject attachment=parsed.getAsJsonArray().get(0).getAsJsonObject();
    return str(attachment,"mimeType").startsWith("image/")?str(attachment,"filename"):null;
  }
  void assign(String key,Status status)throws Exception{JsonObject s=new JsonObject();s.addProperty("id",status.id());JsonObject fields=new JsonObject();fields.add("status",s);edit(key,fields);}
  void edit(String key,JsonObject fields)throws Exception{JsonObject body=new JsonObject();body.add("fields",fields);request("PUT",issuePath(key),body);}
  JsonObject editMeta(String key)throws Exception{return obj(get(issuePath(key)+"/editmeta"),"fields");}
  void transition(String key,String id,JsonObject fields)throws Exception{JsonObject body=new JsonObject(),t=new JsonObject();t.addProperty("id",id);body.add("transition",t);body.add("fields",fields);request("POST",issuePath(key)+"/transitions",body);}
  static String issuePath(String key){if(!key.matches("[A-Za-z][A-Za-z0-9_]*-[0-9]+"))throw new IllegalArgumentException(I18n.t("Неверный ключ задачи"));return "/issue/"+key;}
  static JsonObject obj(JsonObject object,String key){JsonElement e=object.get(key);return e!=null&&e.isJsonObject()?e.getAsJsonObject():new JsonObject();}
  static JsonArray arr(JsonObject object,String key){JsonElement e=object.get(key);return e!=null&&e.isJsonArray()?e.getAsJsonArray():new JsonArray();}
  static String str(JsonObject object,String key){JsonElement e=object.get(key);return e!=null&&e.isJsonPrimitive()?e.getAsString():"";}
  static Status status(JsonObject s){return new Status(str(s,"id"),str(s,"name"),str(obj(s,"statusCategory"),"key"));}
  static String plain(JsonElement value){
    if(value==null||value.isJsonNull())return "";if(value.isJsonPrimitive())return value.getAsString();
    if(value.isJsonArray()){StringBuilder b=new StringBuilder();for(JsonElement e:value.getAsJsonArray())b.append(plain(e));return b.toString();}
    JsonObject o=value.getAsJsonObject();String type=str(o,"type");if(type.equals("text"))return str(o,"text");if(type.equals("hardBreak"))return "\n";
    if(type.equals("mention"))return str(obj(o,"attrs"),"text");String text=plain(o.get("content"));
    return text+(Set.of("paragraph","heading","listItem","blockquote","codeBlock").contains(type)?"\n":"");
  }
  record Status(String id,String name,String category){@Override public String toString(){return name;}}
  record IssueRow(String key,String summary,Status status,String priority){}
  static java.time.Instant commentTime(String date){try{return java.time.OffsetDateTime.parse(date.replaceFirst("([+-][0-9]{2})([0-9]{2})$", "$1:$2")).toInstant();}catch(java.time.format.DateTimeParseException e){return java.time.Instant.MIN;}}
  record Comment(String author,String date,String body){}
  record Media(String name,String url,String thumbnail){}
  record IssueDetails(String key,String summary,Status status,String description,String assignee,String reporter,String priority,List<Comment> comments,List<Media> media,String assigneeName){}
  record Transition(String id,String name,Status to,JsonObject fields){}
}
