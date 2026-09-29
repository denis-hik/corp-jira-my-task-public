package local.corp.jira;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import static local.corp.jira.JiraApi.*;

/** Owns only temporary profile clients; it never closes the task panel's connection. */
final class AccountSettings implements AutoCloseable {
  final Project project;final JTabbedPane tabs;final String host;
  final JPanel page=new JPanel(new BorderLayout());
  SwingWorker<?,?> task;volatile JiraApi client;boolean closed;
  record Profile(String name,String login,String email,String bio,String zone,BufferedImage avatar){}
  AccountSettings(Project project,JTabbedPane tabs){this.project=project;this.tabs=tabs;host=JiraSettings.jira(project);load();}
  void load(){task=new SwingWorker<Profile,Void>(){
    protected Profile doInBackground()throws Exception{
      if(host.isBlank())return null;
      String token=PasswordSafe.getInstance().getPassword(new CredentialAttributes("Corp Jira REST PAT — "+host));if(token==null||token.isBlank()||isCancelled())return null;
      try(JiraApi api=new JiraApi(token,host,JiraTls.savedApproval(host))){client=api;if(isCancelled())return null;var me=api.get("/myself");String bio=str(me,"bio");if(bio.isBlank())bio=str(me,"description");BufferedImage image=null;try{if(!isCancelled())image=api.avatar();}catch(Exception ignored){}return new Profile(str(me,"displayName"),str(me,"name"),str(me,"emailAddress"),bio,str(me,"timeZone"),image);}finally{client=null;}
    }
    protected void done(){if(closed||isCancelled()||project.isDisposed())return;try{Profile profile=get();if(profile==null)return;render(profile);int at=tabs.indexOfTab(I18n.t("Об аддоне"));tabs.insertTab(I18n.t("Аккаунт"),null,page,null,at<0?tabs.getTabCount():at);}catch(Exception ignored){var panel=project.getUserData(JiraToolWindowFactory.ACTIVE_PANEL);if(panel!=null&&panel.api!=null&&host.equals(panel.host)&&panel.api.self!=null){User user=panel.api.self;render(new Profile(user.displayName(),user.name(),"","","",null));int at=tabs.indexOfTab(I18n.t("Об аддоне"));tabs.insertTab(I18n.t("Аккаунт"),null,page,I18n.t("Не удалось обновить профиль Jira"),at<0?tabs.getTabCount():at);}}}
  };task.execute();}
  void render(Profile profile){
    JPanel card=new JPanel(new BorderLayout(20,16));card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new JBColor(new Color(0xCBD8E9),new Color(0x45576F))),BorderFactory.createEmptyBorder(24,24,24,24)));card.setOpaque(false);
    JPanel header=new JPanel(new BorderLayout(18,0));header.setOpaque(false);JLabel photo=new JLabel(new ProfileAvatar(profile.name(),profile.avatar()));header.add(photo,BorderLayout.WEST);
    JPanel identity=new JPanel();identity.setOpaque(false);identity.setLayout(new BoxLayout(identity,BoxLayout.Y_AXIS));JLabel name=JiraToolWindowFactory.label(profile.name());name.setFont(name.getFont().deriveFont(Font.BOLD,22f));identity.add(name);identity.add(Box.createVerticalStrut(6));identity.add(JiraToolWindowFactory.label("@"+profile.login()));header.add(identity);card.add(header,BorderLayout.NORTH);
    JPanel info=new JPanel();info.setOpaque(false);info.setLayout(new BoxLayout(info,BoxLayout.Y_AXIS));info.add(JiraToolWindowFactory.label(I18n.t("О себе")));JTextArea bio=JiraToolWindowFactory.text(profile.bio().isBlank()?I18n.t("Био не указано или недоступно в Jira."):profile.bio(),14);bio.setRows(3);info.add(bio);if(!profile.email().isBlank())info.add(JiraToolWindowFactory.label("Email: "+profile.email()));if(!profile.zone().isBlank())info.add(JiraToolWindowFactory.label(I18n.t("Часовой пояс: ")+profile.zone()));info.add(JiraToolWindowFactory.label("Jira: "+host));for(Component c:info.getComponents())if(c instanceof JComponent jc)jc.setAlignmentX(Component.LEFT_ALIGNMENT);card.add(info);
    JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));actions.setOpaque(false);JButton logout=new JButton(I18n.t("Выйти"));logout.addActionListener(e->logout(logout));actions.add(logout);card.add(actions,BorderLayout.SOUTH);
    page.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));page.removeAll();page.add(card,BorderLayout.NORTH);
  }
  void logout(JButton button){
    var panel=project.getUserData(JiraToolWindowFactory.ACTIVE_PANEL);
    if(panel!=null&&!panel.disposed&&host.equals(panel.host)){if(panel.mutating){Messages.showInfoMessage(project,I18n.t("Дождитесь завершения изменения задачи."),I18n.t("Выход из Jira"));return;}panel.logout(()->{if(!closed)tabs.remove(page);});return;}
    if(Messages.showYesNoDialog(project,I18n.t("Выйти из Jira и удалить сохранённый PAT из WebStorm?"),I18n.t("Выход из Jira"),I18n.t("Выйти"),I18n.t("Отмена"),Messages.getQuestionIcon())!=Messages.YES)return;
    button.setEnabled(false);task=new SwingWorker<Void,Void>(){protected Void doInBackground(){PasswordSafe.getInstance().set(new CredentialAttributes("Corp Jira REST PAT — "+host),null);return null;}protected void done(){if(closed||isCancelled())return;try{get();tabs.remove(page);}catch(Exception error){button.setEnabled(true);Messages.showErrorDialog(project,I18n.t("Не удалось удалить сохранённый PAT."),I18n.t("Выход из Jira"));}}};task.execute();
  }
  public void close(){closed=true;if(task!=null)task.cancel(true);JiraApi active=client;if(active!=null)active.close();page.removeAll();}
  static final class ProfileAvatar implements Icon {
    final String initials;final BufferedImage image;
    ProfileAvatar(String name,BufferedImage image){this.image=image;String[] parts=name.trim().split("\\s+");StringBuilder text=new StringBuilder();for(int i=0;i<Math.min(2,parts.length);i++)if(!parts[i].isEmpty())text.appendCodePoint(parts[i].codePointAt(0));initials=text.toString().toUpperCase(java.util.Locale.ROOT);}
    public int getIconWidth(){return 88;}public int getIconHeight(){return 88;}
    public void paintIcon(Component component,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);var circle=new java.awt.geom.Ellipse2D.Double(x+2,y+2,84,84);g.setColor(new JBColor(new Color(0xD4E5FA),new Color(0x405B7F)));g.fill(circle);if(image!=null){g.clip(circle);g.drawImage(image,x+2,y+2,84,84,null);}else{g.setColor(new JBColor(new Color(0x245695),new Color(0xC0DAFF)));g.setFont(component.getFont().deriveFont(Font.BOLD,28f));FontMetrics fm=g.getFontMetrics();g.drawString(initials,x+(88-fm.stringWidth(initials))/2,y+(88-fm.getHeight())/2+fm.getAscent());}g.setClip(null);g.setColor(new JBColor(new Color(0x9CB8DB),new Color(0x708EAF)));g.draw(circle);}finally{g.dispose();}}
  }
}
