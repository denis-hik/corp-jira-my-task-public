package local.corp.jira;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Non-focus-stealing hover menu; clicking its anchor still opens the full store. */
final class JqlQuickMenu implements AutoCloseable {
  final JButton anchor;
  final Supplier<List<JqlStore.Entry>> entries;
  final Consumer<JqlStore.Entry> apply;
  final Timer delay,watch;
  final MouseAdapter mouse;
  final HierarchyListener hierarchy;
  Popup popup;
  JPanel panel;
  JqlQuickMenu(JButton anchor,Supplier<List<JqlStore.Entry>> entries,Consumer<JqlStore.Entry> apply){
    this.anchor=anchor;this.entries=entries;this.apply=apply;
    delay=new Timer(250,e->show());delay.setRepeats(false);
    watch=new Timer(150,e->{Window window=SwingUtilities.getWindowAncestor(anchor);if(!anchor.isShowing()||!anchor.isEnabled()||window==null||!window.isActive()||!pointerInside())hide();});
    mouse=new MouseAdapter(){public void mouseEntered(MouseEvent e){if(anchor.isEnabled())delay.restart();}public void mouseExited(MouseEvent e){delay.stop();}public void mousePressed(MouseEvent e){hide();}};
    hierarchy=e->{if(!anchor.isShowing())hide();};anchor.addMouseListener(mouse);anchor.addHierarchyListener(hierarchy);
  }
  static List<JqlStore.Entry> firstThree(List<JqlStore.Entry> entries){return entries.stream().limit(3).toList();}
  void show(){
    if(popup!=null||!anchor.isShowing()||!anchor.isEnabled())return;
    Color menuBackground=new com.intellij.ui.JBColor(new Color(0xF1F5FC),new Color(0x2B3545));
    Color menuBorder=new com.intellij.ui.JBColor(new Color(0x9EB4D3),new Color(0x607693));
    Color hoverBackground=new com.intellij.ui.JBColor(new Color(0xD9E8FF),new Color(0x405775));
    panel=new JPanel(new GridLayout(0,1,0,2));panel.setOpaque(true);panel.setBackground(menuBackground);panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(menuBorder),BorderFactory.createEmptyBorder(6,6,6,6)));
    List<JqlStore.Entry> saved=firstThree(entries.get());
    if(saved.isEmpty()){JLabel empty=new JLabel(I18n.t("Нет сохранённых JQL"));empty.setBorder(BorderFactory.createEmptyBorder(6,8,6,8));panel.add(empty);}
    for(JqlStore.Entry entry:saved){JButton item=new JButton(entry.name()){
      @Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(getModel().isRollover()||getModel().isPressed()?hoverBackground:menuBackground);g.fillRoundRect(0,0,getWidth(),getHeight(),8,8);}finally{g.dispose();}super.paintComponent(graphics);}
    };item.setOpaque(false);item.setContentAreaFilled(false);item.setRolloverEnabled(true);item.setForeground(new com.intellij.ui.JBColor(new Color(0x233750),new Color(0xEDF3FC)));item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));item.putClientProperty("html.disable",true);item.setHorizontalAlignment(SwingConstants.LEFT);item.setFocusable(false);item.setBorderPainted(false);item.setMargin(new Insets(6,10,6,10));item.setToolTipText(entry.name());item.addActionListener(e->{hide();if(anchor.isEnabled()&&anchor.isShowing())apply.accept(entry);});panel.add(item);}
    Dimension size=panel.getPreferredSize();size.width=Math.max(180,Math.min(360,size.width));panel.setPreferredSize(size);
    Point point=anchor.getLocationOnScreen();GraphicsConfiguration config=anchor.getGraphicsConfiguration();Rectangle screen=config.getBounds();Insets in=Toolkit.getDefaultToolkit().getScreenInsets(config);screen=new Rectangle(screen.x+in.left,screen.y+in.top,screen.width-in.left-in.right,screen.height-in.top-in.bottom);
    int x=Math.max(screen.x,Math.min(point.x+anchor.getWidth()-size.width,screen.x+screen.width-size.width));int y=point.y+anchor.getHeight();if(y+size.height>screen.y+screen.height)y=point.y-size.height;
    popup=PopupFactory.getSharedInstance().getPopup(anchor,panel,x,y);popup.show();watch.start();
  }
  boolean pointerInside(){PointerInfo info=MouseInfo.getPointerInfo();if(info==null)return false;Point point=info.getLocation();return contains(anchor,point)||contains(panel,point);}
  static boolean contains(Component component,Point point){if(component==null||!component.isShowing())return false;return new Rectangle(component.getLocationOnScreen(),component.getSize()).contains(point);}
  void hide(){delay.stop();watch.stop();if(popup!=null){popup.hide();popup=null;}panel=null;}
  public void close(){hide();anchor.removeMouseListener(mouse);anchor.removeHierarchyListener(hierarchy);}
}
