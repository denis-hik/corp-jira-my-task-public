package local.corp.jira;
import static local.corp.jira.JiraApi.*;
final class TaskExport {
 static String text(String host,IssueDetails i){StringBuilder b=new StringBuilder();b.append(i.key()).append(" — ").append(i.summary()).append("\n").append(host).append("/browse/").append(i.key()).append(I18n.t("\nСтатус: ")).append(i.status().name()).append(I18n.t("\nПриоритет: ")).append(i.priority()).append(I18n.t("\n\nОписание\n")).append(i.description());b.append(I18n.t("\n\nМедиа · ")).append(i.media().size());for(Media m:i.media())b.append("\n").append(m.name()).append("\n").append(m.url());b.append(I18n.t("\n\nКомментарии · ")).append(i.comments().size());for(Comment c:i.comments())b.append("\n\n").append(c.author()).append(" · ").append(JiraToolWindowFactory.date(c.date())).append("\n").append(c.body());return b.toString();}
}
