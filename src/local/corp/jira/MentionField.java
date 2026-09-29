package local.corp.jira;

import com.intellij.ui.JBColor;
import javax.swing.*;
import javax.swing.text.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.*;

/** A normal single-line field; only explicitly selected names become Jira mentions. */
final class MentionField extends JTextField {
  interface Search { void find(String query, Consumer<List<JiraApi.User>> success, Consumer<Throwable> failure); }
  record Span(int start,int end,JiraApi.User user) {}
  record Query(int start,int end,String text) {}
  final List<Span> mentions=new ArrayList<>();
  final DefaultListModel<JiraApi.User> model=new DefaultListModel<>();
  final JList<JiraApi.User> choices=new JList<>(model);
  final JPopupMenu popup=new JPopupMenu();
  final JLabel state=new JLabel();
  final javax.swing.Timer debounce;
  Search search;
  int generation;
  boolean internal,queued;

  MentionField(){
    super(30);
    debounce=new javax.swing.Timer(250,e->lookup());debounce.setRepeats(false);
    popup.setFocusable(false);choices.setFocusable(false);choices.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    choices.setVisibleRowCount(3);choices.setFixedCellHeight(30);
    choices.setCellRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
      JLabel l=(JLabel)super.getListCellRendererComponent(list,value,index,selected,focus);l.putClientProperty("html.disable",true);
      if(value instanceof JiraApi.User u)l.setText(u.displayName()+"  ·  "+u.name());return l;
    }});
    JPanel content=new JPanel(new BorderLayout());content.setBorder(BorderFactory.createEmptyBorder(6,8,6,8));content.add(choices);content.add(state,BorderLayout.SOUTH);popup.add(content);
    choices.addMouseListener(new MouseAdapter(){public void mousePressed(MouseEvent e){int i=choices.locationToIndex(e.getPoint());if(SwingUtilities.isLeftMouseButton(e)&&i>=0&&choices.getCellBounds(i,i).contains(e.getPoint())){choices.setSelectedIndex(i);choose();}}});
    ((AbstractDocument)getDocument()).setDocumentFilter(new DocumentFilter(){
      public void insertString(FilterBypass b,int o,String s,AttributeSet a)throws BadLocationException{replace(b,o,0,s,a);}
      public void remove(FilterBypass b,int o,int n)throws BadLocationException{replace(b,o,n,"",null);}
      public void replace(FilterBypass b,int o,int n,String s,AttributeSet a)throws BadLocationException{
        s=s==null?"":s.replace('\n',' ').replace('\r',' ');int delta=s.length()-n;
        List<Span> updated=new ArrayList<>();
        for(Span m:mentions){if(o+n<=m.start())updated.add(new Span(m.start()+delta,m.end()+delta,m.user()));else if(o>=m.end())updated.add(m);}
        mentions.clear();mentions.addAll(updated);b.replace(o,n,s,a);
      }
    });
    getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){}});
    addCaretListener(e->changed());
    addFocusListener(new FocusAdapter(){public void focusLost(FocusEvent e){SwingUtilities.invokeLater(()->{if(!hasFocus()){dismiss();clean(false);}});}});
    setFocusTraversalKeysEnabled(false);
    bind("TAB",()->{if(!choose()){dismiss();clean(false);transferFocus();}});
    bind("shift TAB",()->{dismiss();clean(false);transferFocusBackward();});
    bind("ENTER",()->{if(!choose()){dismiss();clean(false);postActionEvent();}});
    bind("ESCAPE",()->{dismiss();clean(false);});
    bindArrow("UP",-1);bindArrow("DOWN",1);
  }
  void bind(String key,Runnable run){getInputMap().put(KeyStroke.getKeyStroke(key),"mention-"+key);getActionMap().put("mention-"+key,new AbstractAction(){public void actionPerformed(ActionEvent e){run.run();}});}
  void bindArrow(String key,int delta){Object prior=getInputMap().get(KeyStroke.getKeyStroke(key));Action action=prior==null?null:getActionMap().get(prior);bind(key,()->{if(popup.isVisible()&&!model.isEmpty())choices.setSelectedIndex(Math.floorMod(choices.getSelectedIndex()+delta,model.size()));else if(action!=null)action.actionPerformed(new ActionEvent(this,0,key));});}
  static boolean letter(char c){return Character.isLetterOrDigit(c)||c=='_';}
  boolean start(String text,int i){return text.charAt(i)=='@'&&(i==0||(!letter(text.charAt(i-1))&&".@/-".indexOf(text.charAt(i-1))<0));}
  boolean covered(int i){return mentions.stream().anyMatch(m->i>=m.start()&&i<m.end());}
  Query query(){String t=getText();int end=getCaretPosition(),i=end;while(i>0&&letter(t.charAt(i-1)))i--;if(i>0&&start(t,i-1)&&!covered(i-1)&&getSelectionStart()==getSelectionEnd())return new Query(i-1,end,t.substring(i,end));return null;}
  void changed(){if(internal)return;++generation;debounce.stop();model.clear();popup.setVisible(false);if(queued)return;queued=true;SwingUtilities.invokeLater(()->{queued=false;if(internal)return;clean(true);if(isShowing()&&hasFocus()&&isEditable()&&query()!=null){state.setText(I18n.t("Поиск пользователей…"));showPopup();debounce.restart();}repaint();});}
  void clean(boolean keepActive){Query q=keepActive?query():null;String t=getText();int caret=getCaretPosition();internal=true;try{for(int i=t.length()-1;i>=0;i--)if(start(t,i)&&!covered(i)&&(q==null||i!=q.start())){getDocument().remove(i,1);if(i<caret)caret--;}setCaretPosition(Math.min(caret,getDocument().getLength()));}catch(BadLocationException ignored){}finally{internal=false;}}
  void showPopup(){if(!isShowing()||!hasFocus())return;popup.setPopupSize(Math.max(270,Math.min(440,getWidth())),Math.max(42,model.size()*30+40));try{var r=modelToView2D(getCaretPosition());popup.show(this,Math.max(0,Math.min((int)r.getX(),getWidth()-270)),getHeight());}catch(BadLocationException ignored){}}
  void lookup(){Query q=query();if(q==null||search==null||!hasFocus()||!isShowing())return;int epoch=++generation;search.find(q.text(),users->{if(epoch!=generation||!q.equals(query())||!hasFocus()||!isShowing())return;model.clear();users.stream().limit(3).forEach(model::addElement);if(!model.isEmpty())choices.setSelectedIndex(0);state.setText(model.isEmpty()?I18n.t("Пользователи не найдены"):I18n.t("Tab / Enter — выбрать"));showPopup();},error->{if(epoch!=generation||!hasFocus()||!isShowing())return;model.clear();state.setText(I18n.t("Не удалось найти пользователей"));state.setToolTipText(error.getMessage());showPopup();});}
  boolean choose(){Query q=query();JiraApi.User user=choices.getSelectedValue();if(q==null||user==null||!popup.isVisible())return false;select(q,user);return true;}
  void select(Query q,JiraApi.User user){dismiss();internal=true;try{String name="@"+(user.displayName().isBlank()?user.name():user.displayName());getDocument().remove(q.start(),q.end()-q.start());getDocument().insertString(q.start(),name+" ",null);mentions.add(new Span(q.start(),q.start()+name.length(),user));mentions.sort(Comparator.comparingInt(Span::start));setCaretPosition(q.start()+name.length()+1);}catch(BadLocationException ignored){}finally{internal=false;}repaint();}
  void dismiss(){++generation;debounce.stop();popup.setVisible(false);model.clear();}
  String jiraText(){dismiss();clean(false);StringBuilder body=new StringBuilder(getText());List<Span> sorted=new ArrayList<>(mentions);sorted.sort(Comparator.comparingInt(Span::start).reversed());for(Span m:sorted)body.replace(m.start(),m.end(),"[~"+m.user().name()+"]");return body.toString();}
  @Override public void removeNotify(){dismiss();super.removeNotify();}
  @Override protected void paintComponent(Graphics graphics){super.paintComponent(graphics);if(mentions==null)return;Graphics2D g=(Graphics2D)graphics.create();Insets in=getInsets();g.clipRect(in.left,in.top,getWidth()-in.left-in.right,getHeight()-in.top-in.bottom);g.setFont(getFont());g.setColor(new JBColor(new Color(0x0759B8),new Color(0x85B8FF)));try{for(Span m:mentions){for(int i=m.start();i<m.end();i++){if(i>=getSelectionStart()&&i<getSelectionEnd())continue;var r=modelToView2D(i);g.drawString(getText().substring(i,i+1),(float)r.getX(),(float)r.getY()+getFontMetrics(getFont()).getAscent());}}}catch(BadLocationException ignored){}g.dispose();}
}
