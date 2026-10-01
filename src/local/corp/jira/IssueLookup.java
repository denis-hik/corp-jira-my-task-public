package local.corp.jira;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Parses issue keys without navigating to or requesting a pasted URL. */
final class IssueLookup {
  static String parse(String input,String jiraHost){
    String value=input.trim();
    if(value.matches("(?i)(?:[A-Z][A-Z0-9_]*-[0-9]+|[0-9]+)"))return value.toUpperCase(Locale.ROOT);
    URI link,base;
    try{link=URI.create(value);base=URI.create(jiraHost);}catch(IllegalArgumentException e){throw invalid();}
    if(link.getHost()==null||!("https".equalsIgnoreCase(link.getScheme())||"http".equalsIgnoreCase(link.getScheme())))throw invalid();
    if(link.getUserInfo()!=null||!link.getHost().equalsIgnoreCase(base.getHost())||port(link)!=port(base)||!link.getScheme().equalsIgnoreCase(base.getScheme()))
      throw new IllegalArgumentException("Хост ссылки не совпадает с подключённой Jira.");
    String prefix=base.getPath()==null?"":base.getPath().replaceAll("/+$", "");
    String path=link.getPath();String key=null;
    if(path.startsWith(prefix+"/browse/")){
      key=path.substring((prefix+"/browse/").length()).replaceFirst("/$", "");
    }else if(path.equals(prefix)||path.startsWith(prefix+"/")){
      String query=link.getRawQuery();
      if(query!=null)for(String part:query.split("&")){
        String[] pair=part.split("=",2);
        try{if(pair.length==2&&URLDecoder.decode(pair[0],StandardCharsets.UTF_8).equals("selectedIssue")){
          if(key!=null)throw invalid();key=URLDecoder.decode(pair[1],StandardCharsets.UTF_8);
        }}catch(IllegalArgumentException e){throw invalid();}
      }
    }
    if(key==null||!key.matches("(?i)[A-Z][A-Z0-9_]*-[0-9]+"))throw new IllegalArgumentException("В ссылке не найден корректный ID задачи.");
    return key.toUpperCase(Locale.ROOT);
  }
  private static int port(URI uri){return uri.getPort()!=-1?uri.getPort():"https".equalsIgnoreCase(uri.getScheme())?443:80;}
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("Введите ID задачи или корректную ссылку Jira.");}
}
