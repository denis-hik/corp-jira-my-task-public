package local.corp.jira;
import com.intellij.ide.plugins.PluginManagerCore;import com.intellij.openapi.extensions.PluginId;import com.intellij.openapi.project.Project;import com.intellij.openapi.fileEditor.*;import com.intellij.openapi.editor.Document;import com.intellij.openapi.util.IconLoader;
import javax.swing.*;import java.awt.*;import java.io.File;import java.util.*;import java.util.List;import java.util.function.Consumer;
final class AiChatBridge {
 static ClassLoader loader(){var plugin=PluginManagerCore.getPlugin(PluginId.getId("com.intellij.ml.llm"));if(plugin==null||!plugin.isEnabled())throw new IllegalStateException(I18n.t("JetBrains AI Assistant не включён"));var action=com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("AIAssistant.NewChatInEditor");return action!=null?action.getClass().getClassLoader():plugin.getPluginClassLoader();}
 static boolean available(Project project){return JiraSettings.aiEnabled(project)&&connected(project);}
 static String connectionStatus(Project project){var plugin=PluginManagerCore.getPlugin(PluginId.getId("com.intellij.ml.llm"));if(plugin==null)return I18n.t("JetBrains AI Assistant не установлен.");if(!plugin.isEnabled())return I18n.t("JetBrains AI Assistant отключён.");return connected(project)?I18n.t("Подключено: ChatGPT / Codex доступен."):I18n.t("Подключение не подтверждено: выберите Codex и войдите в AI Assistant.");}
 static boolean usableAgent(boolean disabled,Boolean authenticationRequired,boolean requiresAuthCheck){return !disabled&&!Boolean.TRUE.equals(authenticationRequired)&&(Boolean.FALSE.equals(authenticationRequired)||!requiresAuthCheck);}
 static boolean connected(Project project){
  if(project.isDisposed())return false;
  try{ClassLoader cl=loader();cl.loadClass("com.intellij.ml.llm.core.chat.ui.chat.chatInEditor.AIChatTabInEditorHelperKt").getMethod("openNewChatInEditor",Project.class);
   Class<?> serviceType=cl.loadClass("com.intellij.ml.llm.core.chat.ui.AIAssistantAgentsModelService");Object companion=serviceType.getField("Companion").get(null);Object service=companion.getClass().getMethod("getInstance",Project.class).invoke(companion,project);
   Class<?> model=cl.loadClass("com.intellij.ml.llm.core.chat.ui.AgentModel");for(Object agent:(List<?>)serviceType.getMethod("getAgents").invoke(service)){
    String id=(String)model.getMethod("getId").invoke(agent),name=(String)model.getMethod("getDisplayName").invoke(agent);String identity=(id+" "+name).toLowerCase(Locale.ROOT);
    if(!(identity.contains("codex")||identity.contains("chatgpt")))continue;
    boolean disabled=(Boolean)model.getMethod("isDisabled").invoke(agent);Object authRequired=model.getMethod("isAuthenticationRequired").invoke(agent);
    boolean requiresAuthCheck=(Boolean)model.getMethod("getRequiresAuthCheck").invoke(agent);if(usableAgent(disabled,(Boolean)authRequired,requiresAuthCheck))return true;
   }
  }catch(ReflectiveOperationException|LinkageError|RuntimeException ignored){}return false;
 }
 static Icon icon(){try{return IconLoader.getIcon("/icons/expui/codex.svg",loader().loadClass("com.intellij.ml.llm.core.AIAContentFacade"));}catch(Exception e){return com.intellij.icons.AllIcons.Actions.Preview;}}
 static Object find(Component c){if(c.getClass().getName().equals("com.intellij.ml.llm.core.chat.ui.chat.AIAssistantChatPanel"))return c;if(c instanceof Container parent)for(Component child:parent.getComponents()){Object found=find(child);if(found!=null)return found;}return null;}
 static void open(Project project,String text,List<File> images,java.util.function.BooleanSupplier active,Consumer<String> status){
  if(!available(project)){status.accept(I18n.t("ChatGPT/Codex не подключён в AI Assistant."));return;}
  try{ClassLoader cl=loader();FileEditorManager manager=FileEditorManager.getInstance(project);Set<com.intellij.openapi.vfs.VirtualFile> before=new HashSet<>(Arrays.asList(manager.getOpenFiles()));
   cl.loadClass("com.intellij.ml.llm.core.chat.ui.chat.chatInEditor.AIChatTabInEditorHelperKt").getMethod("openNewChatInEditor",Project.class).invoke(null,project);
   int[] ticks={0};javax.swing.Timer timer=new javax.swing.Timer(250,null);timer.addActionListener(e->{if(project.isDisposed()||!active.getAsBoolean()){timer.stop();return;}try{for(var file:manager.getOpenFiles()){if(before.contains(file)||!file.getClass().getName().equals("com.intellij.ml.llm.core.chat.ui.ChatVirtualFile"))continue;for(FileEditor editor:manager.getEditors(file)){Object panel=find(editor.getComponent());if(panel==null)continue;Object input=panel.getClass().getMethod("getInput").invoke(panel);Class<?> inputType=cl.loadClass("com.intellij.ml.llm.core.chat.ui.chat.input.AIAssistantInput");Document document=(Document)inputType.getMethod("getDocument").invoke(input);if(document.getTextLength()!=0){timer.stop();status.accept(I18n.t("Новый чат уже содержит текст — вставка отменена, чтобы сохранить черновик."));return;}
     timer.stop();panel.getClass().getMethod("setText",String.class).invoke(panel,text);if(!images.isEmpty()){Object context=inputType.getMethod("getContextPanelModel").invoke(input);cl.loadClass("com.intellij.ml.llm.core.chat.ui.chat.context.AIChatContextVm").getMethod("handleFilesDrop",List.class).invoke(context,images);}panel.getClass().getMethod("focusInput").invoke(panel);status.accept(I18n.t("Задача вставлена в новый чат. Медиа переданы как вложения; отправьте сообщение после проверки."));return;
    }}if(++ticks[0]>=80){timer.stop();status.accept(I18n.t("AI Assistant не открыл готовый новый чат за 20 секунд. Текст задачи сохранён в буфере обмена."));}}catch(Exception|LinkageError failure){timer.stop();status.accept(I18n.t("Не удалось заполнить чат: ")+failure.getClass().getSimpleName()+I18n.t(". Текст задачи сохранён в буфере обмена."));}});timer.start();
  }catch(Exception|LinkageError e){status.accept(I18n.t("Не удалось открыть New Chat: ")+e.getClass().getSimpleName()+I18n.t(". Текст задачи сохранён в буфере обмена."));}
 }
}
