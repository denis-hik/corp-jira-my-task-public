package local.corp.jira;

import com.intellij.ide.BrowserUtil;
import com.intellij.ui.JBColor;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.net.URI;
import java.util.regex.*;

/** Jira wiki link labels are rendered as native styled text, never executable HTML. */
final class JiraDescription {
  // Also accepts pasted [label|[URL](URL)] and [label|[URL]] forms.
  static final Pattern LINK=Pattern.compile("\\[([^\\[\\]\\r\\n|]+)\\|(?:\\[(https?://[^\\s\\[\\]]+)\\]\\((https?://[^\\s\\r\\n]+?)\\)\\]|\\[(https?://[^\\s\\[\\]]+)\\]\\]|(https?://[^\\s\\[\\]]+)\\])");
  static boolean safe(String value){try{URI u=URI.create(value);return ("https".equalsIgnoreCase(u.getScheme())||"http".equalsIgnoreCase(u.getScheme()))&&u.getHost()!=null&&u.getUserInfo()==null;}catch(IllegalArgumentException e){return false;}}
  static SimpleAttributeSet linkStyle(String url){SimpleAttributeSet style=new SimpleAttributeSet();StyleConstants.setForeground(style,new JBColor(new Color(0x0759B8),new Color(0x85B8FF)));StyleConstants.setUnderline(style,true);style.addAttribute("url",url);return style;}
  static void appendPlain(StyledDocument document,String text)throws BadLocationException{
    int base=document.getLength();document.insertString(base,text,null);
    for(String url:CommentLinks.urls(text)){if(!safe(url))continue;int at=0;while((at=text.indexOf(url,at))>=0){document.setCharacterAttributes(base+at,url.length(),linkStyle(url),false);at+=url.length();}}
  }
  static JTextPane create(String source){
    JTextPane pane=new JTextPane(){
      @Override public Dimension getPreferredSize(){int width=getParent()!=null&&getParent().getWidth()>40?getParent().getWidth()-getParent().getInsets().left-getParent().getInsets().right:500;Insets in=getInsets();var view=getUI().getRootView(this);view.setSize(Math.max(1,width-in.left-in.right),Integer.MAX_VALUE);return new Dimension(Math.max(40,width),(int)Math.ceil(view.getPreferredSpan(View.Y_AXIS))+in.top+in.bottom+2);}
      @Override public Dimension getMinimumSize(){return new Dimension(0,getPreferredSize().height);}
      @Override public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
    };
    pane.setEditable(false);pane.setOpaque(false);pane.setFont(UIManager.getFont("Label.font").deriveFont(14f));pane.setBorder(BorderFactory.createEmptyBorder(5,0,8,0));pane.setAlignmentX(Component.LEFT_ALIGNMENT);
    StyledDocument document=pane.getStyledDocument();Matcher matcher=LINK.matcher(source);int start=0;
    try{while(matcher.find()){String url=matcher.group(3)!=null?matcher.group(3):matcher.group(4)!=null?matcher.group(4):matcher.group(5);if(!safe(url))continue;appendPlain(document,source.substring(start,matcher.start()));document.insertString(document.getLength(),matcher.group(1),linkStyle(url));start=matcher.end();}appendPlain(document,source.substring(start));}catch(BadLocationException e){throw new IllegalStateException(e);}
    MouseAdapter mouse=new MouseAdapter(){String at(MouseEvent e){int position=pane.viewToModel2D(e.getPoint());if(position<0||position>=document.getLength())return null;try{var start=pane.modelToView2D(position);var end=pane.modelToView2D(position+1);if(e.getY()<start.getY()||e.getY()>=start.getMaxY()||e.getX()<start.getX()||(end.getY()==start.getY()&&e.getX()>end.getX()))return null;}catch(BadLocationException ex){return null;}Object value=document.getCharacterElement(position).getAttributes().getAttribute("url");return value instanceof String s?s:null;}
      public void mouseMoved(MouseEvent e){String url=at(e);pane.setCursor(Cursor.getPredefinedCursor(url==null?Cursor.TEXT_CURSOR:Cursor.HAND_CURSOR));pane.setToolTipText(url);}
      public void mouseClicked(MouseEvent e){if(!SwingUtilities.isLeftMouseButton(e)||pane.getSelectedText()!=null)return;String url=at(e);if(url!=null)BrowserUtil.browse(url);}
    };pane.addMouseListener(mouse);pane.addMouseMotionListener(mouse);return pane;
  }
}
