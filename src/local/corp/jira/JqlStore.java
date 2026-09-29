package local.corp.jira;

import com.google.gson.*;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/** Saved queries are local to the current IDE project. */
final class JqlStore extends DialogWrapper {
  record Entry(String name,String content){@Override public String toString(){return name;}}
  static final String KEY="corp.jira.jql.store";
  final PropertiesComponent properties;
  final DefaultListModel<Entry> model=new DefaultListModel<>();
  final JBList<Entry> list=new JBList<>(model);
  final JTextField name=new JTextField();
  final JTextArea content=new JTextArea(9,45);
  final JLabel notice=new JLabel(" ");
  Entry applied;
  JqlStore(Project project,String current,String currentName){
    super(project);properties=PropertiesComponent.getInstance(project);
    for(Entry entry:decode(properties.getValue(KEY,"[]")))model.addElement(entry);
    setTitle("Store JQL");setOKButtonText(I18n.t("Сохранить и применить"));setCancelButtonText(I18n.t("Закрыть"));
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setCellRenderer((items,value,index,selected,focus)->{JLabel label=new JLabel(value.name());label.putClientProperty("html.disable",true);label.setOpaque(true);label.setBorder(BorderFactory.createEmptyBorder(6,8,6,8));label.setBackground(selected?items.getSelectionBackground():items.getBackground());label.setForeground(selected?items.getSelectionForeground():items.getForeground());return label;});
    list.addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&list.getSelectedValue()!=null){Entry entry=list.getSelectedValue();name.setText(entry.name());content.setText(entry.content());notice.setText(" ");}});
    for(int i=0;i<model.size();i++)if(model.get(i).name().equals(currentName)){list.setSelectedIndex(i);break;}
    name.setText(currentName);content.setText(current);init();
  }
  static List<Entry> decode(String json){List<Entry> entries=new ArrayList<>();try{for(JsonElement item:JsonParser.parseString(json).getAsJsonArray()){JsonObject o=item.getAsJsonObject();String n=o.get("name").getAsString(),c=o.get("content").getAsString();if(!n.isBlank()&&!c.isBlank())entries.add(new Entry(n,c));}}catch(RuntimeException ignored){}return entries;}
  static String encode(List<Entry> entries){JsonArray array=new JsonArray();for(Entry entry:entries){JsonObject o=new JsonObject();o.addProperty("name",entry.name());o.addProperty("content",entry.content());array.add(o);}return array.toString();}
  protected JComponent createCenterPanel(){
    JPanel root=new JPanel(new BorderLayout(12,8));root.setPreferredSize(new Dimension(690,350));
    JPanel saved=new JPanel(new BorderLayout(0,6));saved.add(new JLabel(I18n.t("Сохранённые запросы")),BorderLayout.NORTH);JBScrollPane scroll=new JBScrollPane(list);scroll.setPreferredSize(new Dimension(210,250));saved.add(scroll);
    JPanel tools=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));JButton add=new JButton(I18n.t("Новый")),remove=new JButton(I18n.t("Удалить"));tools.add(add);tools.add(remove);saved.add(tools,BorderLayout.SOUTH);root.add(saved,BorderLayout.WEST);
    JPanel editor=new JPanel(new BorderLayout(0,8));JPanel title=new JPanel(new BorderLayout(0,4));title.add(new JLabel(I18n.t("Название")),BorderLayout.NORTH);title.add(name);editor.add(title,BorderLayout.NORTH);
    JPanel body=new JPanel(new BorderLayout(0,4));body.add(new JLabel("JQL"),BorderLayout.NORTH);content.setLineWrap(true);content.setWrapStyleWord(true);body.add(new JBScrollPane(content));editor.add(body);
    JPanel bottom=new JPanel(new BorderLayout(6,0));JButton save=new JButton(I18n.t("Сохранить"));bottom.add(save,BorderLayout.WEST);bottom.add(notice);editor.add(bottom,BorderLayout.SOUTH);root.add(editor);
    add.addActionListener(e->{list.clearSelection();name.setText("");content.setText("");notice.setText(I18n.t("Новый запрос"));name.requestFocusInWindow();});
    remove.setEnabled(list.getSelectedIndex()>=0);list.addListSelectionListener(e->remove.setEnabled(list.getSelectedIndex()>=0));
    remove.addActionListener(e->{int index=list.getSelectedIndex();if(index<0)return;list.clearSelection();model.remove(index);persist();name.setText("");content.setText("");notice.setText(I18n.t("Удалено"));});
    save.addActionListener(e->{if(saveEntry()!=null)notice.setText(I18n.t("Сохранено"));});return root;
  }
  protected ValidationInfo doValidate(){if(name.getText().isBlank())return new ValidationInfo(I18n.t("Введите название запроса"),name);if(content.getText().isBlank())return new ValidationInfo(I18n.t("Введите JQL"),content);for(int i=0;i<model.size();i++)if(i!=list.getSelectedIndex()&&model.get(i).name().equalsIgnoreCase(name.getText().trim()))return new ValidationInfo(I18n.t("Запрос с таким названием уже есть"),name);return null;}
  Entry saveEntry(){ValidationInfo error=doValidate();if(error!=null){setErrorText(error.message);error.component.requestFocusInWindow();return null;}setErrorText(null);Entry entry=new Entry(name.getText().trim(),content.getText().trim());int index=list.getSelectedIndex();if(index<0){index=model.size();model.addElement(entry);}else model.set(index,entry);list.setSelectedIndex(index);persist();return entry;}
  void persist(){properties.setValue(KEY,encode(Collections.list(model.elements())));}
  protected void doOKAction(){Entry entry=saveEntry();if(entry!=null){applied=entry;super.doOKAction();}}
  public JComponent getPreferredFocusedComponent(){return name;}
}
