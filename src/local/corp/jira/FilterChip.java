package local.corp.jira;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;

/** Native toggle semantics with a compact chip appearance and no checkbox glyph. */
final class FilterChip extends JToggleButton {
  FilterChip(String text,boolean selected){
    super(text,selected);setOpaque(false);setContentAreaFilled(false);setBorderPainted(false);setFocusPainted(false);
    setBorder(BorderFactory.createEmptyBorder(JBUI.scale(4),JBUI.scale(10),JBUI.scale(4),JBUI.scale(10)));
    setRolloverEnabled(true);setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    addItemListener(e->updateHint());updateHint();
  }
  private void updateHint(){setToolTipText(getText()+": "+(isSelected()?I18n.t("включён"):I18n.t("выключен")));}
  @Override public Dimension getPreferredSize(){Insets i=getInsets();FontMetrics fm=getFontMetrics(getFont());return new Dimension(fm.stringWidth(getText())+i.left+i.right,Math.max(JBUI.scale(28),fm.getHeight()+i.top+i.bottom));}
  @Override protected void paintComponent(Graphics graphics){
    Graphics2D g=(Graphics2D)graphics.create();try{
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      if(!isEnabled())g.setComposite(AlphaComposite.SrcOver.derive(.45f));
      Color background=isSelected()?new JBColor(new Color(0xDCEAFF),new Color(0x263F61)):new JBColor(new Color(0xECEEF1),new Color(0x303236));
      if(isEnabled()&&getModel().isRollover())background=isSelected()?new JBColor(new Color(0xCADFFF),new Color(0x304F77)):new JBColor(new Color(0xE0E3E8),new Color(0x3A3D42));
      g.setColor(background);int arc=JBUI.scale(14);g.fillRoundRect(1,1,getWidth()-2,getHeight()-2,arc,arc);
      g.setColor(isSelected()?new JBColor(new Color(0xA9C8F5),new Color(0x486E9B)):new JBColor(new Color(0xD2D6DD),new Color(0x484C53)));g.drawRoundRect(1,1,getWidth()-3,getHeight()-3,arc,arc);
      if(isFocusOwner()){g.setColor(new JBColor(new Color(0x3574F0),new Color(0x85B8FF)));g.setStroke(new BasicStroke(JBUI.scale(2)));g.drawRoundRect(1,1,getWidth()-3,getHeight()-3,arc,arc);}
      g.setColor(isSelected()?new JBColor(new Color(0x174E99),new Color(0xA6CAFF)):getForeground());g.setFont(getFont());FontMetrics fm=g.getFontMetrics();g.drawString(getText(),getInsets().left,(getHeight()-fm.getHeight())/2+fm.getAscent());
    }finally{g.dispose();}
  }
}
