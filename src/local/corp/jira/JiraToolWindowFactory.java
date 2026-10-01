package local.corp.jira;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.Disposable;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ide.CopyPasteManager;
import java.awt.datatransfer.StringSelection;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.*;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.*;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.*;
import com.intellij.ui.content.Content;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static local.corp.jira.JiraApi.*;

public final class JiraToolWindowFactory implements ToolWindowFactory,DumbAware {
  static final com.intellij.openapi.util.Key<Panel> ACTIVE_PANEL=com.intellij.openapi.util.Key.create("corp.jira.activePanel");

  @Override public void createToolWindowContent(Project project,ToolWindow window){
    window.setStripeTitle(I18n.t("Мои задачи Jira"));window.setTitle(I18n.t("Мои задачи Jira"));Panel panel=new Panel(project);Content content=window.getContentManager().getFactory().createContent(panel,"",false);content.setDisposer(panel);window.getContentManager().addContent(content);panel.connect();
  }
  interface Job<T>{T run()throws Exception;}
  static JTextArea text(String value,int size){JTextArea area=new JTextArea(value){@Override public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}};area.setEditable(false);area.setLineWrap(true);area.setWrapStyleWord(true);area.setOpaque(false);area.setFont(UIManager.getFont("Label.font").deriveFont((float)size));area.setBorder(BorderFactory.createEmptyBorder(5,0,8,0));return area;}
  static void iconButton(JButton button,String tooltip){button.setBorderPainted(false);button.setContentAreaFilled(false);button.setOpaque(false);button.setBorder(BorderFactory.createEmptyBorder(2,2,2,2));button.setMargin(new Insets(0,0,0,0));String caption=button.getText();int textWidth=caption==null||caption.isEmpty()?0:button.getFontMetrics(button.getFont()).stringWidth(caption);int iconWidth=button.getIcon()==null?0:button.getIcon().getIconWidth();int width=Math.max(28,textWidth+iconWidth+(textWidth>0&&iconWidth>0?button.getIconTextGap():0)+4);Dimension size=new Dimension(width,30);button.setPreferredSize(size);button.setMinimumSize(size);button.setMaximumSize(size);button.setToolTipText(tooltip);button.getAccessibleContext().setAccessibleName(tooltip);button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));}
  static <T extends JComponent> void detachDrafts(Map<String,T> drafts,java.util.function.Predicate<T> keep){for(var it=drafts.values().iterator();it.hasNext();){T component=it.next();Container parent=component.getParent();if(parent!=null)parent.remove(component);if(!keep.test(component))it.remove();}}
  static JLabel label(String s){JLabel l=new JLabel(s);l.putClientProperty("html.disable",true);return l;}
  static String error(Throwable e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m;}
  static String date(String s){try{return OffsetDateTime.parse(s.replaceFirst("([+-][0-9]{2})([0-9]{2})$", "$1:$2")).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));}catch(Exception ignored){return s;}}
  static final class DetailPanel extends JPanel implements Scrollable {
    public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
    public int getScrollableUnitIncrement(Rectangle r,int o,int d){return 20;}
    public int getScrollableBlockIncrement(Rectangle r,int o,int d){return Math.max(20,r.height-20);}
    public boolean getScrollableTracksViewportWidth(){return true;}
    public boolean getScrollableTracksViewportHeight(){return false;}
  }
  static final class IssueIdIcon implements Icon {
    public int getIconWidth(){return 18;}public int getIconHeight(){return 18;}
    public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);g.setColor(c.getForeground());g.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD,11f));g.drawString("ID",x+1,y+13);g.dispose();}
  }
  static final class Panel extends JPanel implements Disposable {
    final Project project;
    JButton aiButton;final javax.swing.Timer aiAvailabilityTimer=new javax.swing.Timer(3000,e->refreshAiAvailability());
    void refreshAiAvailability(){if(aiButton!=null&&isShowing()){boolean visible=AiChatBridge.available(project);if(aiButton.isVisible()!=visible){aiButton.setVisible(visible);details.revalidate();details.repaint();}}}
    final ThreadPoolExecutor worker=new ThreadPoolExecutor(2,2,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(128),r->{Thread t=new Thread(r,"Corp Jira REST");t.setDaemon(true);return t;});
    final Set<Future<?>> jobs=ConcurrentHashMap.newKeySet();final List<Future<?>> cardJobs=new ArrayList<>();final Set<com.intellij.openapi.ui.popup.JBPopup> cardPopups=new HashSet<>();int cardEpoch;Future<?> listFuture;
    final DefaultListModel<IssueRow> model=new DefaultListModel<>();final JBList<IssueRow> list=new JBList<>(model);final List<IssueRow> rows=new ArrayList<>();
    final JTextField search=new JTextField();final JPanel details=new DetailPanel();final JTextArea message=text(I18n.t("Подключение к Jira…"),12);
    final JButton refresh=new JButton(AllIcons.Actions.Refresh),openIssue=new JButton(new IssueIdIcon()),account=new JButton(I18n.t("Войти")),change=new JButton(),refreshIssue=new JButton(AllIcons.Actions.Refresh),stop=new JButton(I18n.t("Остановить"));
    final JPanel filterRow=new JPanel(new BorderLayout(4,0)), presets=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));
    final FilterChip mine=new FilterChip(I18n.t("На мне"),true), active=new FilterChip(I18n.t("Незавершённые"),true);
    final JLabel customLabel=label(I18n.t("Кастомный фильтр"));final JButton editFilter=new JButton(),storeJql=new JButton(AllIcons.Actions.MenuSaveall);
    final Map<String,Composer> drafts=new HashMap<>();
    String host,gitHost;volatile int session=0;
    CredentialAttributes credentials(){return new CredentialAttributes("Corp Jira REST PAT — "+host);}
    String customJql="",customJqlName="";JqlQuickMenu quickJql;
    final AtomicBoolean cancelled=new AtomicBoolean();
    volatile boolean disposed=false;boolean mutating=false,rendering=false;JiraApi api;IssueDetails shown;int listGeneration=0,detailGeneration=0;Future<?> detailFuture;
    Panel(Project project){
      super(new BorderLayout(8,6));this.project=project;project.putUserData(ACTIVE_PANEL,this);aiAvailabilityTimer.start();host=JiraSettings.jira(project);gitHost=JiraSettings.git(project);setBorder(BorderFactory.createEmptyBorder(6,6,6,6));
      JPanel toolbar=new JPanel(new FlowLayout(FlowLayout.LEFT,2,0));iconButton(refresh,I18n.t("Обновить список задач"));iconButton(openIssue,I18n.t("Перейти к задаче по ID"));toolbar.add(refresh);toolbar.add(openIssue);toolbar.add(stop);stop.setVisible(false);iconButton(change,I18n.t("Изменить статус"));change.setIcon(AllIcons.General.ArrowDown);change.setHorizontalTextPosition(SwingConstants.LEFT);change.setIconTextGap(8);iconButton(refreshIssue,I18n.t("Обновить карточку"));change.getAccessibleContext().setAccessibleName(I18n.t("Изменить статус"));change.setEnabled(false);refreshIssue.setEnabled(false);JPanel top=new JPanel(new BorderLayout());top.add(toolbar,BorderLayout.WEST);JPanel right=new JPanel();right.setLayout(new BoxLayout(right,BoxLayout.X_AXIS));JButton settings=new JButton(AllIcons.General.Settings);iconButton(settings,I18n.t("Настройки Jira и GitLab"));settings.addActionListener(e->{if(mutating)return;com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project,JiraSettings.class);syncSettings();});right.add(settings);right.add(Box.createHorizontalStrut(4));right.add(account);top.add(right,BorderLayout.EAST);add(top,BorderLayout.NORTH);
      JPanel left=new JPanel(new BorderLayout(0,6));JPanel filters=new JPanel(new BorderLayout(0,4));filters.add(search,BorderLayout.NORTH);presets.add(mine);presets.add(active);filterRow.add(presets);JPanel filterActions=new JPanel(new FlowLayout(FlowLayout.RIGHT,2,0));iconButton(storeJql,I18n.t("Store JQL — сохранённые запросы"));iconButton(editFilter,I18n.t("Редактировать JQL"));filterActions.add(storeJql);filterActions.add(editFilter);filterRow.add(filterActions,BorderLayout.EAST);filters.add(filterRow);left.add(filters,BorderLayout.NORTH);left.add(new JBScrollPane(list));left.setMinimumSize(new Dimension(250,120));
      search.setToolTipText(I18n.t("Поиск по ключу, названию и статусу"));search.getAccessibleContext().setAccessibleName(I18n.t("Поиск задач Jira"));
      list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);list.setCellRenderer((items,value,index,selected,focus)->{
        JPanel cell=new JPanel(new BorderLayout(0,4));cell.setBorder(BorderFactory.createEmptyBorder(9,8,9,8));Color fg=selected?items.getSelectionForeground():items.getForeground();cell.setBackground(selected?items.getSelectionBackground():items.getBackground());
        JLabel key=label(value.key()+"  ·  "+value.status().name());key.setFont(key.getFont().deriveFont(Font.BOLD));key.setForeground(fg);JLabel title=label(value.summary());title.setForeground(fg);cell.add(key,BorderLayout.NORTH);cell.add(title);return cell;
      });
      details.setLayout(new BoxLayout(details,BoxLayout.Y_AXIS));details.setBorder(BorderFactory.createEmptyBorder(12,14,12,14));empty(I18n.t("Выберите задачу слева"));
      JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,left,new JBScrollPane(details));split.setResizeWeight(.30);split.setDividerLocation(370);add(split);
      message.setRows(2);add(message,BorderLayout.SOUTH);
      list.addListSelectionListener(e->{if(!rendering&&!e.getValueIsAdjusting()&&!mutating&&list.getSelectedValue()!=null)loadDetails(list.getSelectedValue().key());});
      search.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){renderList();}public void removeUpdate(DocumentEvent e){renderList();}public void changedUpdate(DocumentEvent e){renderList();}});
      refresh.addActionListener(e->loadList());openIssue.addActionListener(e->{if(api!=null&&!mutating)new IssueLookupDialog().show();});account.addActionListener(e->{if(api==null)configure();else logout();});refreshIssue.addActionListener(e->{if(shown!=null)loadDetails(shown.key());});change.addActionListener(e->chooseStatus());stop.addActionListener(e->{cancelled.set(true);message.setText(I18n.t("Остановим после текущего запроса. Выполненные изменения сохранятся."));});
      customJql=PropertiesComponent.getInstance(project).getValue("corp.jira.jql","");customJqlName=PropertiesComponent.getInstance(project).getValue("corp.jira.jql.name","");updateFilter();
      quickJql=new JqlQuickMenu(storeJql,()->JqlStore.decode(PropertiesComponent.getInstance(project).getValue(JqlStore.KEY,"[]")),entry->{if(!mutating&&api!=null)applyQuery(entry.content(),entry.name());});storeJql.setToolTipText(null);
      storeJql.addActionListener(e->{quickJql.hide();JqlStore dialog=new JqlStore(project,query(),customJqlName);if(dialog.showAndGet()&&dialog.applied!=null)applyQuery(dialog.applied.content(),dialog.applied.name());});editFilter.addActionListener(e->editQuery());mine.addActionListener(e->{filterChanged();});active.addActionListener(e->{filterChanged();});authUi();
    }
    boolean syncSettings(){String next=JiraSettings.jira(project),git=JiraSettings.git(project);boolean changed=!next.equals(host);gitHost=git;if(changed){host=next;closeSession();++listGeneration;++detailGeneration;if(detailFuture!=null)detailFuture.cancel(true);api=null;shown=null;drafts.clear();rows.clear();model.clear();empty(I18n.t("Войдите в Jira"));connect();}else if(shown!=null)showDetails(shown);return changed;}
    void filterChanged(){++detailGeneration;if(detailFuture!=null)detailFuture.cancel(true);shown=null;change.setEnabled(false);refreshIssue.setEnabled(false);rows.clear();model.clear();empty(I18n.t("Выберите задачу из обновлённого списка"));updateFilter();loadList();}
    String query(){if(!customJql.isBlank())return customJql;return (mine.isSelected()?"assignee = currentUser()":"")+(mine.isSelected()&&active.isSelected()?" AND ":"")+(active.isSelected()?"status NOT IN (Closed, Done, Resolved, Declined, Rejected)":"");}
    void updateFilter(){filterRow.remove(presets);filterRow.remove(customLabel);filterRow.add(customJql.isBlank()?presets:customLabel,BorderLayout.CENTER);customLabel.setText(I18n.t("Кастомный фильтр")+(customJqlName.isBlank()?"":": "+customJqlName));editFilter.setText("");editFilter.setIcon(customJql.isBlank()?AllIcons.Actions.Edit:AllIcons.Actions.Close);iconButton(editFilter,customJql.isBlank()?I18n.t("Редактировать JQL"):I18n.t("Отменить кастомный JQL"));filterRow.setToolTipText(query());filterRow.revalidate();filterRow.repaint();}
    void applyQuery(String content,String name){customJql=content;customJqlName=name;PropertiesComponent p=PropertiesComponent.getInstance(project);p.setValue("corp.jira.jql",content,"");p.setValue("corp.jira.jql.name",name,"");filterChanged();}
    void editQuery(){if(!customJql.isBlank()){applyQuery("","");return;}String value=Messages.showMultilineInputDialog(project,I18n.t("Запрос Jira (JQL)"),I18n.t("Редактировать фильтр"),query(),Messages.getQuestionIcon(),null);if(value==null||value.isBlank())return;applyQuery(value.trim(),"");}
    void setAccount(String name){account.setText("");account.setIcon(new AvatarIcon(name));iconButton(account,name);account.setToolTipText(name);JiraApi client=api;if(client!=null)background(client::avatar,image->{if(api==client&&image!=null){AvatarIcon icon=new AvatarIcon(name);icon.image=image;account.setIcon(icon);}},ignored->{});}
    void authUi(){boolean logged=api!=null;refresh.setVisible(logged);openIssue.setVisible(logged);change.setVisible(logged);refreshIssue.setVisible(logged);filterRow.setVisible(logged);search.setVisible(logged);account.setEnabled(true);if(!logged){account.setText(I18n.t("Войти"));account.setIcon(null);account.setPreferredSize(null);account.setMinimumSize(null);account.setMaximumSize(null);account.setToolTipText(I18n.t("Войти в Jira"));account.setBorderPainted(true);account.setContentAreaFilled(true);}revalidate();repaint();}
    void logout(){logout(()->{});}
    void logout(Runnable onSuccess){if(mutating)return;if(Messages.showYesNoDialog(project,I18n.t("Выйти из Jira и удалить сохранённый PAT из WebStorm?"),I18n.t("Выход из Jira"),I18n.t("Выйти"),I18n.t("Отмена"),Messages.getQuestionIcon())!=Messages.YES)return;account.setEnabled(false);background(()->{PasswordSafe.getInstance().set(credentials(),null);return true;},ignored->{closeSession();++listGeneration;++detailGeneration;if(detailFuture!=null)detailFuture.cancel(true);shown=null;rows.clear();model.clear();empty(I18n.t("Войдите в Jira"));authUi();message.setText(I18n.t("Вы вышли из Jira."));onSuccess.run();},e->{account.setEnabled(true);message.setText(I18n.t("Не удалось удалить PAT: ")+error(e));});}
    static void releaseResult(Object result){if(result instanceof JiraApi client)client.close();else if(result instanceof Map.Entry<?,?> entry&&entry.getKey() instanceof JiraApi client)client.close();}
    <T> Future<?> background(Job<T> job,java.util.function.Consumer<T> done,java.util.function.Consumer<Throwable> failed){
      int epoch=session;
      FutureTask<Void> task=new FutureTask<>(()->{if(disposed||epoch!=session)return null;try{T result=job.run();SwingUtilities.invokeLater(()->{if(!disposed&&epoch==session)done.accept(result);else releaseResult(result);});}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();SwingUtilities.invokeLater(()->{if(!disposed&&epoch==session)failed.accept(e);});}return null;}){@Override protected void done(){jobs.remove(this);}};
      if(disposed){task.cancel(false);return task;}jobs.add(task);try{worker.execute(task);}catch(RejectedExecutionException e){task.cancel(false);if(!disposed)failed.accept(new IllegalStateException(I18n.t("Слишком много запросов. Повторите после завершения текущих.")));}return task;
    }
    <T> void cardBackground(Job<T> job,java.util.function.Consumer<T> done,java.util.function.Consumer<Throwable> failed){int epoch=cardEpoch;cardJobs.removeIf(Future::isDone);cardJobs.add(background(job,result->{if(epoch==cardEpoch)done.accept(result);},error->{if(epoch==cardEpoch)failed.accept(error);}));}
    void trackPopup(com.intellij.openapi.ui.popup.JBPopup popup){cardPopups.add(popup);popup.addListener(new com.intellij.openapi.ui.popup.JBPopupListener(){public void onClosed(com.intellij.openapi.ui.popup.LightweightWindowEvent event){cardPopups.remove(popup);}});}
    void clearCard(){
      for(var popup:List.copyOf(cardPopups))popup.cancel();cardPopups.clear();
      ++cardEpoch;for(Future<?> task:cardJobs)task.cancel(true);cardJobs.clear();worker.purge();aiButton=null;for(JButton button:List.of(change,refreshIssue)){Container parent=button.getParent();if(parent!=null)parent.remove(button);}
      // A retained composer must not retain its old parent and all sibling comments.
      detachDrafts(drafts,composer->{composer.input.dismiss();if(composer.mentionSearch!=null)composer.mentionSearch.cancel(true);return composer.sending||composer.uncertain||!composer.input.getText().isEmpty()||!composer.files.isEmpty();});
      details.removeAll();
    }
    void closeSession(){if(quickJql!=null)quickJql.hide();++session;for(Future<?> task:jobs)task.cancel(true);worker.purge();if(api!=null)api.close();api=null;clearCard();drafts.clear();}
    void connect(){if(host.isBlank()){authUi();message.setText(I18n.t("Нажмите «Войти» и введите персональный токен Jira."));return;}String authHost=host;CredentialAttributes authCredentials=credentials();authUi();account.setEnabled(false);background(()->{String secret=PasswordSafe.getInstance().getPassword(authCredentials);if(secret==null||secret.isBlank())return null;JiraApi candidate=new JiraApi(secret,authHost,JiraTls.savedApproval(authHost));try{return Map.entry(candidate,str(candidate.get("/myself"),"displayName"));}catch(Exception e){candidate.close();throw e;}},result->{account.setEnabled(true);if(result==null){message.setText(I18n.t("Нажмите «Войти» и введите персональный токен Jira."));return;}api=result.getKey();setAccount(result.getValue());authUi();loadList();},e->{api=null;authUi();message.setText(I18n.t("Войдите снова: ")+error(e));});}
    void configure(){
      if(mutating)return;TokenDialog dialog=new TokenDialog(project,host);if(!dialog.showAndGet())return;char[] chars=dialog.password.getPassword();String secret=new String(chars).trim();Arrays.fill(chars,'\0');dialog.password.setText("");
      String authHost=dialog.verifiedHost;CredentialAttributes authCredentials=new CredentialAttributes("Corp Jira REST PAT — "+authHost);account.setEnabled(false);message.setText(I18n.t("Сохраняем проверенное подключение…"));background(()->{PasswordSafe.getInstance().set(authCredentials,new Credentials("PAT",secret));return dialog.verifiedApi;},candidate->{JiraTls.saveApproval(authHost,dialog.acceptedFingerprint);host=authHost;PropertiesComponent.getInstance(project).setValue("corp.jira.host",host);api=candidate;account.setEnabled(true);setAccount(dialog.verifiedUser);authUi();loadList();},e->{dialog.verifiedApi.close();account.setEnabled(true);message.setText(I18n.t("Подключение не сохранено: ")+error(e));});
    }
    void loadList(){
      if(mutating)return;if(!host.equals(JiraSettings.jira(project))){syncSettings();return;}gitHost=JiraSettings.git(project);if(api==null){message.setText(I18n.t("Сначала настройте подключение по PAT."));return;}int generation=++listGeneration;JiraApi client=api;refresh.setEnabled(false);message.setText(I18n.t("Загрузка списка по вашему JQL…"));
      String jql=query();if(listFuture!=null)listFuture.cancel(true);worker.purge();listFuture=background(()->client.issues(jql),result->{if(generation!=listGeneration)return;refresh.setEnabled(true);rows.clear();rows.addAll(result);renderList();message.setText(result.isEmpty()?I18n.t("Нет задач, соответствующих вашему JQL."):I18n.t("Задач: ")+rows.size()+I18n.t(". Выберите задачу для просмотра."));},e->{if(generation!=listGeneration)return;refresh.setEnabled(true);rows.clear();model.clear();message.setText(error(e));});
    }
    void renderList(){rendering=true;try{String key=list.getSelectedValue()==null?null:list.getSelectedValue().key();String q=search.getText().toLowerCase(Locale.ROOT);model.clear();for(IssueRow row:rows)if((row.key()+" "+row.summary()+" "+row.status().name()).toLowerCase(Locale.ROOT).contains(q)){model.addElement(row);if(row.key().equals(key))list.setSelectedValue(row,true);}}finally{rendering=false;}}
    void loadDetails(String key){
      if(api==null||mutating||disposed)return;shown=null;int generation=++detailGeneration;if(detailFuture!=null)detailFuture.cancel(true);change.setEnabled(false);refreshIssue.setEnabled(false);empty(I18n.t("Загрузка ")+key+"…");JiraApi client=api;
      worker.purge();detailFuture=background(()->client.details(key),result->{if(generation==detailGeneration){shown=result;showDetails(result);change.setEnabled(true);refreshIssue.setEnabled(true);message.setText(I18n.t("Карточка обновлена: ")+key);}},e->{if(generation==detailGeneration){shown=null;empty(I18n.t("Не удалось загрузить задачу"));message.setText(error(e));}});
    }
    final class IssueLookupDialog extends DialogWrapper {
      final JTextField id=new JTextField(26);
      final JLabel failure=label(" ");
      Future<?> request;boolean closed;
      IssueLookupDialog(){super(Panel.this.project);setTitle(I18n.t("Перейти к задаче по ID"));setOKButtonText(I18n.t("Открыть"));init();}
      @Override protected JComponent createCenterPanel(){
        JPanel body=new JPanel(new BorderLayout(0,4));body.add(label(I18n.t("ID задачи или ссылка Jira")),BorderLayout.NORTH);body.add(id);
        failure.setForeground(JBColor.RED);body.add(failure,BorderLayout.SOUTH);
        id.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){clear();}public void removeUpdate(DocumentEvent e){clear();}public void changedUpdate(DocumentEvent e){clear();}void clear(){failure.setText(" ");}});return body;
      }
      @Override public JComponent getPreferredFocusedComponent(){return id;}
      @Override protected void doOKAction(){
        if(request!=null&&!request.isDone())return;
        String key;
        try{key=IssueLookup.parse(id.getText(),host);}catch(IllegalArgumentException e){failure.setText(I18n.t(e.getMessage()));return;}
        JiraApi client=api;if(client==null||mutating||disposed)return;
        setOKActionEnabled(false);id.setEnabled(false);failure.setText(" ");
        request=background(()->client.details(key),issue->{
          if(closed)return;
          ++detailGeneration;if(detailFuture!=null)detailFuture.cancel(true);
          rendering=true;try{list.clearSelection();}finally{rendering=false;}
          shown=issue;showDetails(issue);change.setEnabled(true);refreshIssue.setEnabled(true);
          message.setText(I18n.t("Карточка обновлена: ")+issue.key());close(OK_EXIT_CODE);
        },e->{if(closed)return;setOKActionEnabled(true);id.setEnabled(true);
          failure.setText(e instanceof ApiException a&&a.status==404?I18n.t("Задача не найдена или недоступна."):I18n.t("Не удалось открыть задачу: ")+error(e));id.requestFocusInWindow();});
      }
      @Override protected void dispose(){closed=true;if(request!=null)request.cancel(true);super.dispose();}
    }
    void empty(String s){clearCard();details.add(text(s,16));details.revalidate();details.repaint();}

    void block(JComponent c){c.setAlignmentX(Component.LEFT_ALIGNMENT);c.setMaximumSize(new Dimension(Integer.MAX_VALUE,c.getPreferredSize().height));details.add(c);}
    void showDetails(IssueDetails issue){
      clearCard();
      JPanel header=new JPanel(new BorderLayout(8,0));JPanel status=new JPanel();status.setLayout(new BoxLayout(status,BoxLayout.X_AXIS));
      JButton key=new JButton(issue.key());iconButton(key,I18n.t("Открыть ")+issue.key()+I18n.t(" в браузере"));key.setForeground(new JBColor(new Color(0x2459AA),new Color(0x85B8FF)));String issueUrl=host+"/browse/"+issue.key();key.addActionListener(e->{if((e.getModifiers()&java.awt.event.ActionEvent.CTRL_MASK)==0)BrowserUtil.browse(issueUrl);});
      key.addMouseListener(new java.awt.event.MouseAdapter(){
        com.intellij.openapi.ui.popup.Balloon copied;
        boolean handled;
        void copyUrl(java.awt.event.MouseEvent e){if(!key.isEnabled()||handled)return;handled=true;CopyPasteManager.getInstance().setContents(new StringSelection(issueUrl));if(copied!=null)copied.hide();JLabel hint=label(I18n.t("URL скопирован"));hint.setBorder(BorderFactory.createEmptyBorder(4,8,4,8));copied=JBPopupFactory.getInstance().createBalloonBuilder(hint).setFadeoutTime(1600).setHideOnClickOutside(true).setHideOnKeyOutside(true).setAnimationCycle(0).createBalloon();copied.show(new com.intellij.ui.awt.RelativePoint(key,new Point(key.getWidth()/2,0)),com.intellij.openapi.ui.popup.Balloon.Position.above);e.consume();}
        public void mousePressed(java.awt.event.MouseEvent e){handled=false;if(e.isPopupTrigger()||SwingUtilities.isRightMouseButton(e))copyUrl(e);}
        public void mouseReleased(java.awt.event.MouseEvent e){if(e.isPopupTrigger()||SwingUtilities.isRightMouseButton(e))copyUrl(e);handled=false;}
      });status.add(key);status.add(Box.createHorizontalStrut(12));change.setText(issue.status().name());iconButton(change,I18n.t("Изменить статус"));change.setForeground(new JBColor(new Color(0x2459AA),new Color(0x85B8FF)));status.add(change);header.add(status);JPanel actions=new JPanel();actions.setLayout(new BoxLayout(actions,BoxLayout.X_AXIS));JButton copyTask=new JButton(AllIcons.Actions.Copy),chatTask=new JButton(AiChatBridge.icon());iconButton(copyTask,I18n.t("Скопировать задачу целиком"));iconButton(chatTask,I18n.t("Новый чат ChatGPT / Codex с задачей"));aiButton=chatTask;chatTask.setVisible(AiChatBridge.available(project));copyTask.addActionListener(e->{CopyPasteManager.getInstance().setContents(new StringSelection(TaskExport.text(host,issue)));message.setText(I18n.t("Задача скопирована: описание, ссылки на медиа и комментарии с временем."));});chatTask.addActionListener(e->exportToChat(issue,chatTask));actions.add(copyTask);actions.add(chatTask);actions.add(refreshIssue);header.add(actions,BorderLayout.EAST);block(header);
      JTextArea title=text(issue.summary(),20);title.setFont(title.getFont().deriveFont(Font.BOLD));block(title);
      JPanel assigneeRow=new JPanel();assigneeRow.setLayout(new BoxLayout(assigneeRow,BoxLayout.X_AXIS));assigneeRow.add(label(I18n.t("Исполнитель: ")));JButton assignee=new JButton(issue.assignee().isBlank()?I18n.t("Не назначен"):issue.assignee(),AllIcons.General.ArrowDown);assignee.setHorizontalTextPosition(SwingConstants.LEFT);iconButton(assignee,I18n.t("Изменить исполнителя"));assignee.addActionListener(e->chooseAssignee(issue,assignee));assigneeRow.add(assignee);
      if(api!=null&&api.self!=null&&!api.self.name().isBlank()&&!api.self.name().equals(issue.assigneeName())){JButton me=new JButton(new AssignMeIcon());iconButton(me,I18n.t("Назначить на меня"));me.addActionListener(e->assignUser(issue,api.self));assigneeRow.add(me);}assigneeRow.add(label(I18n.t("   ·   Приоритет: ")+issue.priority()));block(assigneeRow);block(label(I18n.t("Автор: ")+issue.reporter()));details.add(Box.createVerticalStrut(14));
      JPanel descriptionHeader=new JPanel(new BorderLayout());JLabel desc=label(I18n.t("Описание"));desc.setFont(desc.getFont().deriveFont(Font.BOLD));descriptionHeader.add(desc);
      JButton copy=new JButton(AllIcons.Actions.Copy);iconButton(copy,I18n.t("Копировать описание"));copy.getAccessibleContext().setAccessibleName(I18n.t("Копировать описание"));copy.addActionListener(e->{CopyPasteManager.getInstance().setContents(new StringSelection(issue.description()));message.setText(I18n.t("Описание скопировано"));});descriptionHeader.add(copy,BorderLayout.EAST);block(descriptionHeader);
      details.add(JiraDescription.create(issue.description().isBlank()?I18n.t("Описание отсутствует"):issue.description()));details.add(Box.createVerticalStrut(16));
      JButton mediaToggle=new JButton(I18n.t("▸ Медиа · ")+issue.media().size());mediaToggle.setHorizontalAlignment(SwingConstants.LEFT);block(mediaToggle);
      JPanel strip=new JPanel();strip.setLayout(new BoxLayout(strip,BoxLayout.X_AXIS));
      JBScrollPane mediaScroll=new JBScrollPane(strip,ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);mediaScroll.setPreferredSize(new Dimension(400,245));mediaScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE,245));mediaScroll.setAlignmentX(Component.LEFT_ALIGNMENT);mediaScroll.setVisible(false);details.add(mediaScroll);
      AtomicBoolean mediaLoaded=new AtomicBoolean();int mediaGeneration=detailGeneration;JiraApi mediaClient=api;
      mediaToggle.addActionListener(e->{boolean visible=!mediaScroll.isVisible();mediaScroll.setVisible(visible);mediaToggle.setText((visible?"▾":"▸")+I18n.t(" Медиа · ")+issue.media().size());
        if(visible&&mediaLoaded.compareAndSet(false,true)){
          if(issue.media().isEmpty())strip.add(label(I18n.t("Нет изображений")));
          for(Media media:issue.media()){
            JPanel tile=new JPanel(new BorderLayout(4,4));tile.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));tile.setPreferredSize(new Dimension(290,210));tile.setMaximumSize(new Dimension(290,210));
            JButton preview=new JButton(I18n.t("Загрузка…"));preview.setToolTipText(media.name());preview.setEnabled(false);JLabel name=label(media.name());name.setToolTipText(media.name());tile.add(preview);tile.add(name,BorderLayout.SOUTH);strip.add(tile);
            cardBackground(()->mediaClient.mediaImage(media.url(),320,200),image->{if(mediaGeneration!=detailGeneration||api!=mediaClient)return;double scale=Math.min(1,Math.min(270.0/image.getWidth(),165.0/image.getHeight()));preview.setText("");preview.setIcon(new ImageIcon(image.getScaledInstance(Math.max(1,(int)(image.getWidth()*scale)),Math.max(1,(int)(image.getHeight()*scale)),Image.SCALE_SMOOTH)));preview.setEnabled(true);preview.addActionListener(click->{preview.setEnabled(false);cardBackground(()->mediaClient.mediaImage(media.url()),fullImage->{preview.setEnabled(true);JLabel full=new JLabel(new ImageIcon(fullImage));JBScrollPane pane=new JBScrollPane(full);pane.setPreferredSize(new Dimension(Math.min(1000,fullImage.getWidth()+30),Math.min(700,fullImage.getHeight()+30)));new DialogWrapper(project){ {setTitle(media.name());init();setOKButtonText(I18n.t("Закрыть"));}protected JComponent createCenterPanel(){return pane;}protected Action[] createActions(){return new Action[]{getOKAction()};}}.show();},failure->{preview.setEnabled(true);message.setText(error(failure));});});},failure->{if(mediaGeneration!=detailGeneration||api!=mediaClient)return;preview.setText(I18n.t("Не удалось загрузить"));preview.setToolTipText(error(failure));});
          }
        }details.revalidate();details.repaint();});
      details.add(Box.createVerticalStrut(12));
      JButton toggle=new JButton(I18n.t("▸ Комментарии · ")+issue.comments().size());toggle.setHorizontalAlignment(SwingConstants.LEFT);block(toggle);
      JPanel chat=new JPanel(){@Override public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}};chat.setLayout(new BoxLayout(chat,BoxLayout.Y_AXIS));chat.setAlignmentX(Component.LEFT_ALIGNMENT);chat.setVisible(false);
      chat.add(drafts.computeIfAbsent(issue.key(),Composer::new));
      if(issue.comments().isEmpty()){JLabel emptyComments=label(I18n.t("Нету комментариев"));emptyComments.setAlignmentX(Component.LEFT_ALIGNMENT);emptyComments.setForeground(UIManager.getColor("Label.disabledForeground"));emptyComments.setBorder(BorderFactory.createEmptyBorder(0,0,8,0));chat.add(emptyComments);}
      int[] renderedComments={0};JButton moreComments=new JButton(I18n.t("Показать ещё комментарии"));
      Runnable appendComments=()->{chat.remove(moreComments);int end=Math.min(renderedComments[0]+50,issue.comments().size());while(renderedComments[0]<end){chat.add(Box.createVerticalStrut(10));chat.add(new CommentBubble(issue.comments().get(renderedComments[0]++),this));}if(renderedComments[0]<issue.comments().size()){moreComments.setText(I18n.t("Показать ещё · ")+(issue.comments().size()-renderedComments[0]));chat.add(moreComments);}chat.revalidate();details.revalidate();details.repaint();};
      moreComments.addActionListener(e->appendComments.run());
      toggle.addActionListener(e->{boolean visible=!chat.isVisible();if(visible&&renderedComments[0]==0)appendComments.run();chat.setVisible(visible);toggle.setText((visible?"▾":"▸")+I18n.t(" Комментарии · ")+issue.comments().size());details.revalidate();details.repaint();});details.add(chat);
      details.add(Box.createVerticalGlue());details.revalidate();details.repaint();
    }
    final class Composer extends JPanel {
      final String key;final MentionField input=new MentionField();final JPanel filesRow=new JPanel(new FlowLayout(FlowLayout.LEFT,6,2));final List<java.io.File> files=new ArrayList<>();
      final JBScrollPane filesScroll=new JBScrollPane(filesRow,ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
      final JButton attach=new JButton(new ComposeIcon(false)),send=new JButton(new ComposeIcon(true));boolean sending=false,uncertain=false;Future<?> mentionSearch;
      Composer(String key){super(new BorderLayout(6,6));this.key=key;setAlignmentX(Component.LEFT_ALIGNMENT);setMinimumSize(new Dimension(0,50));setBorder(BorderFactory.createEmptyBorder(10,0,12,0));
        input.search=(query,success,failure)->{JiraApi client=api;if(client==null)return;if(mentionSearch!=null)mentionSearch.cancel(true);worker.purge();mentionSearch=background(()->client.mentionUsers(query),users->{if(api==client)success.accept(users);},failure);};
        input.setToolTipText(I18n.t("Напишите комментарий или перетащите файлы сюда"));input.getAccessibleContext().setAccessibleName(I18n.t("Новый комментарий"));
        JPanel editor=new JPanel(new BorderLayout(6,0));input.setMinimumSize(new Dimension(0,30));input.setPreferredSize(new Dimension(160,30));editor.add(input);JPanel buttons=new JPanel();buttons.setLayout(new BoxLayout(buttons,BoxLayout.X_AXIS));iconButton(attach,I18n.t("Прикрепить файлы"));iconButton(send,I18n.t("Отправить комментарий"));for(JButton button:List.of(attach,send)){button.setMargin(new Insets(0,0,0,0));button.setBorder(BorderFactory.createEmptyBorder());button.setPreferredSize(new Dimension(28,28));button.setMinimumSize(new Dimension(28,28));button.setMaximumSize(new Dimension(28,28));}buttons.add(attach);buttons.add(Box.createHorizontalStrut(4));buttons.add(send);editor.add(buttons,BorderLayout.EAST);add(editor);
        filesScroll.setPreferredSize(new Dimension(300,59));filesScroll.setBorder(BorderFactory.createEmptyBorder());filesScroll.setVisible(false);add(filesScroll,BorderLayout.SOUTH);
        attach.addActionListener(e->{JFileChooser chooser=new JFileChooser();chooser.setMultiSelectionEnabled(true);if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)addFiles(Arrays.asList(chooser.getSelectedFiles()));});
        TransferHandler original=input.getTransferHandler();input.setTransferHandler(new TransferHandler(){public boolean canImport(TransferSupport support){return !sending&&(support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)||original.canImport(support));}public boolean importData(TransferSupport support){if(sending)return false;if(!support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor))return original.importData(support);try{Object data=support.getTransferable().getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);if(data instanceof List<?> list){List<java.io.File> added=new ArrayList<>();for(Object item:list)if(item instanceof java.io.File file)added.add(file);addFiles(added);return true;}}catch(Exception ex){message.setText(error(ex));}return false;}});
        send.addActionListener(e->submit());
      }
      public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
      void addFiles(List<java.io.File> added){for(var file:added)if(file.isFile()&&file.canRead()&&!files.contains(file))files.add(file);renderFiles();}
      void renderFiles(){filesRow.removeAll();for(var file:files){JPanel chip=new JPanel(new BorderLayout(4,0)){
          @Override protected void paintComponent(Graphics graphics){super.paintComponent(graphics);Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(new JBColor(new Color(0xB6C5D8),new Color(0x4C607A)));g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,14,14);g.dispose();}
        };chip.setOpaque(false);chip.setBorder(BorderFactory.createEmptyBorder(3,9,3,4));JLabel name=label(file.getName());name.setMinimumSize(new Dimension(0,20));name.setToolTipText(file.getName());chip.add(name,BorderLayout.CENTER);JButton preview=new JButton(AllIcons.Actions.Preview),remove=new JButton(AllIcons.Actions.Close);iconButton(preview,I18n.t("Просмотреть ")+file.getName());iconButton(remove,I18n.t("Убрать ")+file.getName());
        JPanel actions=new JPanel();actions.setOpaque(false);actions.setLayout(new BoxLayout(actions,BoxLayout.X_AXIS));for(JButton button:List.of(preview,remove)){button.setBorder(BorderFactory.createEmptyBorder());button.setMargin(new Insets(0,0,0,0));button.setPreferredSize(new Dimension(24,24));button.setMinimumSize(new Dimension(24,24));button.setMaximumSize(new Dimension(24,24));actions.add(button);}
        preview.addActionListener(e->{var virtualFile=LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);if(virtualFile==null){message.setText(I18n.t("Файл не найден: ")+file.getName());return;}FileEditorManager.getInstance(project).openFile(virtualFile,true);});remove.setEnabled(!sending);remove.addActionListener(e->{files.remove(file);renderFiles();});chip.add(actions,BorderLayout.EAST);chip.setPreferredSize(new Dimension(Math.min(220,chip.getPreferredSize().width),34));chip.setMaximumSize(new Dimension(220,34));filesRow.add(chip);}filesScroll.setVisible(!files.isEmpty());filesRow.revalidate();filesRow.repaint();revalidate();details.revalidate();details.repaint();}
      void submit(){if(sending||mutating||api==null)return;if(uncertain){message.setText(I18n.t("Результат предыдущей отправки неизвестен. Проверьте Jira; черновик сохранён, автоповтора нет."));return;}String body=input.jiraText().trim();if(body.isEmpty()&&files.isEmpty())return;
        sending=true;input.setEditable(false);attach.setEnabled(false);send.setEnabled(false);lock(true);stop.setVisible(false);renderFiles();List<java.io.File> batch=List.copyOf(files),uploaded=new ArrayList<>();JiraApi client=api;message.setText(I18n.t("Отправка комментария и файлов…"));
        background(()->{for(var file:batch){client.upload(key,file.toPath());uploaded.add(file);}if(!body.isEmpty())client.comment(key,body);return true;},ok->{files.clear();input.setText("");finish();loadDetails(key);message.setText(I18n.t("Отправлено. Обновляем комментарии…"));},failure->{files.removeAll(uploaded);uncertain=!(failure instanceof ApiException);finish();message.setText(error(failure)+I18n.t("\nЗагружено файлов: ")+uploaded.size()+I18n.t(". Неотправленный текст и файлы сохранены."));});
      }
      void finish(){sending=false;input.setEditable(true);attach.setEnabled(true);send.setEnabled(!uncertain);lock(false);renderFiles();}
    }
    void exportToChat(IssueDetails issue,JButton button){if(api==null)return;if(!AiChatBridge.available(project)){button.setVisible(false);return;}JiraApi client=api;String text=TaskExport.text(host,issue);CopyPasteManager.getInstance().setContents(new StringSelection(text));button.setEnabled(false);message.setText(I18n.t("Готовим задачу и изображения для нового чата…"));background(()->{List<java.io.File> files=new ArrayList<>();if(!issue.media().isEmpty()){java.nio.file.Path root=java.nio.file.Path.of(com.intellij.openapi.application.PathManager.getSystemPath(),"corp-jira-export");java.nio.file.Files.createDirectories(root);java.nio.file.Path dir=java.nio.file.Files.createTempDirectory(root,issue.key()+"-");int n=0;for(Media media:issue.media())files.add(client.exportMedia(media,dir,++n).toFile());}return files;},files->{button.setEnabled(true);AiChatBridge.open(project,text,files,()->!disposed&&api==client,msg->{if(!disposed)message.setText(msg);});},failure->{button.setEnabled(true);message.setText(I18n.t("Не удалось подготовить медиа: ")+error(failure)+I18n.t(". Текст задачи скопирован; новый чат не создан."));});}
    void chooseAssignee(IssueDetails issue,JButton anchor){
      if(mutating||api==null)return;JiraApi client=api;JPanel menu=new JPanel(new BorderLayout(0,6));menu.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));menu.setPreferredSize(new Dimension(380,320));JTextField query=new JTextField();query.setToolTipText(I18n.t("Поиск исполнителя по имени или логину"));DefaultListModel<User> users=new DefaultListModel<>();JBList<User> options=new JBList<>(users);options.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);JLabel state=label(I18n.t("Загрузка…"));menu.add(query,BorderLayout.NORTH);menu.add(new JBScrollPane(options));menu.add(state,BorderLayout.SOUTH);
      var popup=JBPopupFactory.getInstance().createComponentPopupBuilder(menu,query).setTitle(I18n.t("Исполнитель")).setFocusable(true).setRequestFocus(true).setCancelOnClickOutside(true).createPopup();int[] generation={0};Future<?>[] lookup={null};
      Runnable searchUsers=()->{if(popup.isDisposed())return;int current=++generation[0];state.setText(I18n.t("Поиск…"));String term=query.getText().trim();if(lookup[0]!=null)lookup[0].cancel(true);worker.purge();lookup[0]=background(()->client.assignable(issue.key(),term),result->{if(popup.isDisposed()||current!=generation[0])return;users.clear();result.forEach(users::addElement);if(!users.isEmpty())options.setSelectedIndex(0);state.setText(result.isEmpty()?I18n.t("Пользователи не найдены"):result.size()==50?I18n.t("Первые 50 — уточните поиск"):I18n.t("Выберите исполнителя"));},e->{if(!popup.isDisposed()&&current==generation[0]){users.clear();state.setText(error(e));}});};
      javax.swing.Timer debounce=new javax.swing.Timer(300,e->searchUsers.run());debounce.setRepeats(false);popup.addListener(new com.intellij.openapi.ui.popup.JBPopupListener(){public void onClosed(com.intellij.openapi.ui.popup.LightweightWindowEvent event){debounce.stop();++generation[0];if(lookup[0]!=null)lookup[0].cancel(true);worker.purge();}});query.getDocument().addDocumentListener(new DocumentListener(){void changed(){++generation[0];users.clear();debounce.restart();}public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){changed();}});
      Runnable choose=()->{User selected=options.getSelectedValue();if(selected!=null){debounce.stop();popup.cancel();if(api==client&&shown!=null&&shown.key().equals(issue.key()))assignUser(issue,selected);}};
      options.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseClicked(java.awt.event.MouseEvent e){if(e.getButton()==1){int index=options.locationToIndex(e.getPoint());if(index>=0&&options.getCellBounds(index,index).contains(e.getPoint()))choose.run();}}});query.addActionListener(e->choose.run());options.getInputMap().put(KeyStroke.getKeyStroke("ENTER"),"assign");options.getActionMap().put("assign",new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){choose.run();}});
      trackPopup(popup);popup.showUnderneathOf(anchor);searchUsers.run();
    }
    void assignUser(IssueDetails issue,User user){if(mutating||api==null||user==null||shown==null||!shown.key().equals(issue.key()))return;if(user.name().equals(issue.assigneeName()))return;JiraApi client=api;lock(true);stop.setVisible(false);message.setText(I18n.t("Назначаем: ")+user.displayName()+"…");background(()->{client.assignUser(issue.key(),user);IssueDetails result=client.details(issue.key());if(!result.assigneeName().equals(user.name()))throw new java.io.IOException(I18n.t("Jira не подтвердила выбранного исполнителя. Обновите карточку; автоповтора нет."));return result;},result->{lock(false);shown=result;showDetails(result);loadList();message.setText(I18n.t("Исполнитель: ")+result.assignee());},e->{lock(false);message.setText(error(e));});}
    void chooseStatus(){
      if(shown==null||mutating)return;String key=shown.key();int mode=JiraSettings.statusMode(project);JiraApi client=api;change.setEnabled(false);message.setText(I18n.t("Загрузка доступных переходов…"));
      background(()->{Status current=client.current(key);List<Transition> transitions=client.transitions(key);return Map.entry(current,StatusFlow.choices(current,transitions,mode==2?List.of():client.statuses()).stream().filter(c->mode==0||(mode==1?c.route()!=null:c.direct())).toList());},data->{change.setEnabled(true);if(shown==null||!shown.key().equals(key))return;
        var statusPopup=JBPopupFactory.getInstance().createPopupChooserBuilder(data.getValue()).setTitle(I18n.t("Выбрать статус")).setNamerForFiltering(c->c.status().name())
          .setRenderer((ListCellRenderer<StatusFlow.Choice>)(items,value,index,selected,focus)->{JLabel l=label((value.direct()?"":"⚠  ")+value.status().name()+"  ·  "+value.hint());l.setBorder(BorderFactory.createEmptyBorder(8,10,8,10));l.setOpaque(true);l.setBackground(selected?items.getSelectionBackground():items.getBackground());l.setForeground(selected?items.getSelectionForeground():value.direct()?items.getForeground():new JBColor(new Color(0xB53636),new Color(0xFF7C80)));return l;})
          .setItemChosenCallback(choice->{if(shown==null||!shown.key().equals(key)||mutating)return;
        String fill=JiraSettings.requiredText(project);
        if(choice.direct()){fill=Messages.showInputDialog(project,I18n.t("Введите текст для обязательных текстовых полей перехода."),I18n.t("Назначить статус"),Messages.getQuestionIcon(),fill,new com.intellij.openapi.ui.InputValidator(){public boolean checkInput(String value){return !value.isBlank();}public boolean canClose(String value){return checkInput(value);}});if(fill==null)return;runStatus(client,key,data.getKey(),choice,fill.trim());return;}
        String route=choice.route()==null?I18n.t("Резервный маршрут неизвестен"):String.join(" → ",choice.route().stream().map(Status::name).toList());
        String confirm=I18n.t("Задача: ")+key+I18n.t("\nЦель: ")+choice.status().name()+I18n.t("\n\nСначала прямая попытка. При явном отказе Jira:\n")+route+I18n.t("\n\nТекст обязательных полей: ")+fill+I18n.t("\nКаждый шаг изменяет задачу. При ошибке выполненные изменения сохраняются.");
        if(Messages.showYesNoDialog(project,confirm,I18n.t("Назначить статус"),I18n.t("Назначить"),I18n.t("Отмена"),Messages.getQuestionIcon())==Messages.YES)runStatus(client,key,data.getKey(),choice,fill);
        }) .createPopup();trackPopup(statusPopup);statusPopup.showUnderneathOf(change);
      },e->{change.setEnabled(true);message.setText(error(e));});
    }
    void lock(boolean value){if(value&&quickJql!=null)quickJql.hide();storeJql.setEnabled(!value);editFilter.setEnabled(!value);mine.setEnabled(!value);active.setEnabled(!value);mutating=value;list.setEnabled(!value);search.setEnabled(!value);refresh.setEnabled(!value);openIssue.setEnabled(!value);account.setEnabled(!value);change.setEnabled(!value&&shown!=null);refreshIssue.setEnabled(!value&&shown!=null);stop.setVisible(value);}
    void runStatus(JiraApi client,String key,Status initial,StatusFlow.Choice choice,String fill){
      lock(true);cancelled.set(false);message.setText(I18n.t("Назначаем ")+choice.status().name()+"…");
      background(()->{StatusFlow.execute(StatusFlow.gateway(client,key),initial,choice,()->cancelled.get()||disposed,msg->SwingUtilities.invokeLater(()->{if(!disposed)message.setText(msg);}),fill);return client.details(key);},result->{lock(false);shown=result;showDetails(result);loadList();message.setText(I18n.t("Готово: ")+key+" → "+result.status().name()+I18n.t(". Карточка обновлена."));},e->{lock(false);message.setText(error(e)+I18n.t("\nПроверяем текущий статус…"));String failure=error(e);background(()->client.details(key),result->{shown=result;showDetails(result);message.setText(failure+I18n.t("\nТекущий статус: ")+result.status().name()+I18n.t(". Автоматического отката нет."));},readError->message.setText(failure+I18n.t("\nНе удалось проверить текущий статус. Обновите карточку.")));});
    }
    @Override public void dispose(){disposed=true;if(project.getUserData(ACTIVE_PANEL)==this)project.putUserData(ACTIVE_PANEL,null);if(quickJql!=null)quickJql.close();aiAvailabilityTimer.stop();cancelled.set(true);closeSession();worker.shutdownNow();shown=null;rows.clear();model.clear();account.setIcon(null);removeAll();}
  }
  static final class TokenDialog extends DialogWrapper {
    final JPasswordField password=new JPasswordField(36);final JTextField hostField=new JTextField(36);final JLabel status=label(I18n.t("Адрес и PAT будут проверены перед сохранением."));
    String verifiedHost,verifiedUser;JiraApi verifiedApi;SwingWorker<Void,Void> check;javax.swing.Timer deadline;String acceptedFingerprint;String acceptedHost;
    TokenDialog(Project project,String host){super(project);hostField.setText(host);setTitle(I18n.t("Подключение к Jira"));setOKButtonText(I18n.t("Проверить и войти"));init();}
    @Override protected JComponent createCenterPanel(){JPanel p=new JPanel(new BorderLayout(0,10));JPanel fields=new JPanel(new GridLayout(0,1,0,5));fields.add(label(I18n.t("Хост Jira")));fields.add(hostField);fields.add(label("Personal Access Token"));fields.add(password);fields.add(status);p.add(fields);JPanel bottom=new JPanel(new BorderLayout(0,6));bottom.add(text(I18n.t("Токен хранится в Password Safe WebStorm.\nВ чат и в файлы проекта его передавать не нужно."),12));JButton generate=new JButton(I18n.t("Создать PAT в Jira ↗"));generate.addActionListener(e->{try{String host=JiraSettings.normalize(hostField.getText(),false);BrowserUtil.browse(host+"/secure/ViewProfile.jspa?selectedTab=com.atlassian.pats.pats-plugin:jira-user-personal-access-tokens");}catch(Exception ex){setErrorText(error(ex));}});bottom.add(generate,BorderLayout.SOUTH);p.add(bottom,BorderLayout.SOUTH);return p;}
    @Override protected void doOKAction(){
      if(check!=null)return;String host;try{host=JiraSettings.normalize(hostField.getText(),false);}catch(Exception e){setErrorText(error(e));return;}char[] chars=password.getPassword();String secret=new String(chars).trim();Arrays.fill(chars,'\0');if(secret.isEmpty()){setErrorText(I18n.t("Введите PAT"));return;}
      if(!host.equals(acceptedHost)){acceptedHost=host;acceptedFingerprint=JiraTls.savedApproval(host);}setErrorText(null);setOKActionEnabled(false);hostField.setEnabled(false);password.setEnabled(false);status.setText(I18n.t("Проверяем доступность Jira и PAT…"));
      check=new SwingWorker<>(){JiraApi candidate;String user;
        protected Void doInBackground()throws Exception{candidate=new JiraApi(secret,host,acceptedFingerprint);try{var info=candidate.get("/serverInfo");if(str(info,"version").isBlank())throw new java.io.IOException(I18n.t("Сервер не подтвердил Jira REST API"));var me=candidate.get("/myself");user=str(me,"displayName");if(user.isBlank())throw new java.io.IOException(I18n.t("Jira не подтвердила пользователя PAT"));return null;}finally{if(isCancelled())candidate.close();}}
        protected void done(){if(isCancelled()){if(candidate!=null)candidate.close();return;}if(deadline!=null)deadline.stop();try{get();verifiedHost=host;verifiedUser=user;verifiedApi=candidate;complete();}catch(Exception e){if(candidate!=null)candidate.close();Throwable cause=e.getCause()==null?e:e.getCause();setErrorText(error(cause));status.setText(I18n.t("Проверка не пройдена. Подключение не сохранено."));hostField.setEnabled(true);password.setEnabled(true);setOKActionEnabled(true);check=null;
          JiraTls.Failure certificate=candidate==null?null:candidate.tls.failure;if(certificate!=null){String warning=I18n.t("Не удалось проверить сертификат сервера:\n")+host+I18n.t("\n\nПричина: ")+certificate.reason()+I18n.t("\nСертификат: ")+certificate.subject()+I18n.t("\nИздатель: ")+certificate.issuer()+"\nSHA-256: "+certificate.fingerprint()+I18n.t("\n\nПодключение на свой страх и риск: поддельный сервер может получить ваш PAT и данные задач.\nСохранить доверие к этому сертификату для этого хоста, включая следующие запуски WebStorm? При смене недоверенного сертификата потребуется новое подтверждение.");
            if(Messages.showYesNoDialog((Project)null,warning,I18n.t("Недоверенный сертификат Jira"),I18n.t("Подключиться на свой риск"),I18n.t("Отмена"),Messages.getWarningIcon())==Messages.YES){acceptedHost=host;acceptedFingerprint=certificate.fingerprint();doOKAction();}}
        }}
      };deadline=new javax.swing.Timer(40000,e->{if(check!=null){check.cancel(true);check=null;hostField.setEnabled(true);password.setEnabled(true);setOKActionEnabled(true);status.setText(I18n.t("Время ожидания истекло. Подключение не сохранено."));setErrorText(I18n.t("Jira не ответила за 40 секунд. Проверьте хост, VPN и доступность сервера."));}});deadline.setRepeats(false);deadline.start();check.execute();
    }

    void complete(){if(deadline!=null)deadline.stop();check=null;status.setText(I18n.t("Подключение проверено. Выполняем вход…"));setOKActionEnabled(true);super.doOKAction();}
    @Override protected void dispose(){if(deadline!=null)deadline.stop();if(check!=null)check.cancel(true);super.dispose();}
    @Override public void doCancelAction(){if(deadline!=null)deadline.stop();if(check!=null)check.cancel(true);password.setText("");super.doCancelAction();}
    @Override public JComponent getPreferredFocusedComponent(){return hostField;}
  }
  static final class AssignMeIcon implements Icon {
    public int getIconWidth(){return 18;}public int getIconHeight(){return 18;}
    public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();g.translate(x,y);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(c.getForeground());g.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.drawOval(3,2,5,5);g.drawArc(1,9,10,9,0,180);g.drawLine(12,7,16,7);g.drawLine(14,5,16,7);g.drawLine(14,9,16,7);g.dispose();}
  }
  static final class ComposeIcon implements Icon {
    final boolean send;ComposeIcon(boolean send){this.send=send;}public int getIconWidth(){return 20;}public int getIconHeight(){return 20;}
    public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();g.translate(x,y);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(c.isEnabled()?c.getForeground():UIManager.getColor("Label.disabledForeground"));g.setStroke(new BasicStroke(1.6f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
      if(send){java.awt.geom.Path2D p=new java.awt.geom.Path2D.Double();p.moveTo(2,3);p.lineTo(18,10);p.lineTo(2,17);p.lineTo(5,10);p.closePath();g.draw(p);g.drawLine(5,10,17,10);}else{java.awt.geom.Path2D p=new java.awt.geom.Path2D.Double();p.moveTo(7,12);p.lineTo(13,6);p.curveTo(16,3,19,6,16,9);p.lineTo(9,16);p.curveTo(4,21,-1,15,4,10);p.lineTo(11,3);g.draw(p);}g.dispose();}
  }
  static final class AvatarIcon implements Icon {
    final String initials;final Color color;java.awt.image.BufferedImage image;
    AvatarIcon(String name){String[] words=name.trim().split("\\s+");String first=words.length>0&&!words[0].isEmpty()?words[0].substring(0,words[0].offsetByCodePoints(0,1)):"?";String last=words.length>1?words[words.length-1].substring(0,words[words.length-1].offsetByCodePoints(0,1)):"";initials=(first+last).toUpperCase(Locale.ROOT);color=Color.getHSBColor(Math.floorMod(name.hashCode(),360)/360f,.55f,.68f);}
    public int getIconWidth(){return 26;}public int getIconHeight(){return 26;}
    public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
      if(image!=null){Graphics2D photo=(Graphics2D)g.create();photo.clip(new java.awt.geom.Ellipse2D.Double(x+2,y+2,22,22));photo.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);photo.drawImage(image,x+2,y+2,22,22,null);photo.dispose();}
      else{g.setColor(color);g.fillOval(x+2,y+2,22,22);g.setColor(Color.WHITE);g.setFont(c.getFont().deriveFont(Font.BOLD,10f));FontMetrics fm=g.getFontMetrics();g.drawString(initials,x+(26-fm.stringWidth(initials))/2,y+(26-fm.getHeight())/2+fm.getAscent());}
      g.setColor(new JBColor(new Color(0x8FA7C2),new Color(0x607A99)));g.setStroke(new BasicStroke(1f));g.draw(new java.awt.geom.Ellipse2D.Double(x+.5,y+.5,25,25));g.dispose();}

  }
  static final class CommentBubble extends JPanel {
    JTextPane body;final JPanel content=new JPanel(new BorderLayout());final JButton expand=new JButton();boolean expanded;final String fullText;
    static String preview(String text){int end=Math.min(text.length(),700),lines=0;for(int i=0;i<end;i++)if(text.charAt(i)=='\n'&&++lines==8){end=i;break;}if(end<text.length()&&end>0&&Character.isHighSurrogate(text.charAt(end-1)))end--;return end<text.length()?text.substring(0,end).stripTrailing()+"…":text;}
    CommentBubble(Comment comment,Panel owner){super(new BorderLayout(0,5));fullText=comment.body();setOpaque(false);setBorder(BorderFactory.createEmptyBorder(12,14,10,14));setAlignmentX(Component.LEFT_ALIGNMENT);
      JLabel author=label(comment.author());author.setFont(author.getFont().deriveFont(Font.BOLD));author.setForeground(new JBColor(new Color(0x0747A6),new Color(0x85B8FF)));add(author,BorderLayout.NORTH);
      body=CommentLinks.text(preview(fullText));body.setMinimumSize(new Dimension(0,0));content.setOpaque(false);content.add(body);JPanel links=new JPanel();links.setOpaque(false);links.setLayout(new BoxLayout(links,BoxLayout.Y_AXIS));content.add(links,BorderLayout.SOUTH);add(content);expand.setText(I18n.t("Раскрыть больше"));iconButton(expand,I18n.t("Раскрыть больше"));expand.setAlignmentX(Component.LEFT_ALIGNMENT);expand.setVisible(!preview(fullText).equals(fullText));links.add(expand);expand.addActionListener(e->{expanded=!expanded;content.remove(body);body=CommentLinks.text(expanded?fullText:preview(fullText));body.setMinimumSize(new Dimension(0,0));content.add(body,BorderLayout.CENTER);expand.setText(expanded?I18n.t("Свернуть"):I18n.t("Раскрыть больше"));iconButton(expand,expand.getText());revalidate();if(owner!=null)owner.details.revalidate();repaint();});String gitHost=owner==null?"":owner.gitHost;for(String url:CommentLinks.urls(comment.body()))if(CommentLinks.commit(url,gitHost)!=null)owner.cardBackground(()->CommentLinks.find(owner.project,url,gitHost),commit->{if(commit!=null){JButton jump=new JButton("Git Log ↗ "+commit.hash().asString().substring(0,8));iconButton(jump,I18n.t("Открыть коммит в Git Log"));jump.addActionListener(e->CommentLinks.open(owner.project,commit));links.add(jump);owner.details.revalidate();owner.details.repaint();}},ignored->{});JLabel time=label(date(comment.date()));time.setFont(time.getFont().deriveFont(11f));time.setForeground(new JBColor(new Color(0x526B8D),new Color(0xA7BBD5)));time.setHorizontalAlignment(SwingConstants.RIGHT);add(time,BorderLayout.SOUTH);
    }
    @Override public Dimension getPreferredSize(){int width=getParent()!=null&&getParent().getWidth()>40?getParent().getWidth():500;int inner=Math.max(40,width-28);Insets insets=body.getInsets();var view=body.getUI().getRootView(body);view.setSize(Math.max(1,inner-insets.left-insets.right),Integer.MAX_VALUE);int height=(int)Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS))+insets.top+insets.bottom+2;body.setPreferredSize(new Dimension(inner,height));Dimension size=super.getPreferredSize();return new Dimension(width,size.height);}
    @Override public Dimension getMinimumSize(){return new Dimension(0,getPreferredSize().height);}
    @Override public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
    @Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(new JBColor(new Color(0xE9F2FF),new Color(0x203652)));g.fillRoundRect(0,0,getWidth(),getHeight(),18,18);g.dispose();super.paintComponent(graphics);}
  }
}
