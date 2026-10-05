package local.corp.jira;

import com.intellij.openapi.project.Project;
import git4idea.GitUtil;
import java.net.URI;
import java.util.*;

/** Compare repository identity, never just its display name or a commit hash. */
final class ProjectRepositories {
  static Set<String> current(Project project){
    Set<String> result=new HashSet<>();
    if(!project.isDisposed())for(var repo:GitUtil.getRepositories(project))for(var remote:repo.getRemotes())for(String url:remote.getUrls()){
      String id=identity(url,false);if(!id.isEmpty())result.add(id);
    }
    return result;
  }
  static String identity(String value,boolean entity){
    if(value==null||value.isBlank())return "";
    try{
      String normalized=value.matches("^[^/@:]+@[^/:]+:.+")?"ssh://"+value.replaceFirst(":","/"):value;
      URI uri=URI.create(normalized);if(uri.getHost()==null)return "";
      String path=uri.getPath();if(path==null)return "";
      if(entity){
        int marker=path.indexOf("/-/");
        if(marker>=0)path=path.substring(0,marker);
        else {var matcher=java.util.regex.Pattern.compile("^(.+?)/(?:commit|commits|tree|branches|pull|pull-requests|merge_requests)/.+$").matcher(path);if(!matcher.matches())return "";path=matcher.group(1);}
      }
      path=path.replaceAll("/+$","").replaceFirst("\\.git$","");
      if(path.isEmpty()||path.equals("/"))return "";
      return uri.getHost().toLowerCase(Locale.ROOT)+path;
    }catch(IllegalArgumentException e){return "";}
  }
  static boolean matches(JiraDevelopment.Entry entry,Set<String> repositories){
    String id=identity(entry.repositoryUrl(),false);
    if(id.isEmpty())id=identity(entry.url(),true);
    return !id.isEmpty()&&repositories.contains(id);
  }
}
