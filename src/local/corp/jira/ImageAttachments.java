package local.corp.jira;
import java.util.List;
final class ImageAttachments {
  static String comment(String body,List<String> images){
    StringBuilder result=new StringBuilder(body);
    for(String name:images){
      // Jira delimiters cannot occur in a wiki image filename.
      if(name.isBlank()||name.matches("(?s).*[!|\\r\\n].*"))continue;
      if(!result.isEmpty())result.append('\n');
      result.append('!').append(name).append("|thumbnail!");
    }
    return result.toString();
  }
}
