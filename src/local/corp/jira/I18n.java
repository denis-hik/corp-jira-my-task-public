package local.corp.jira;

import com.intellij.DynamicBundle;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.*;

/** UI language is fixed for the IDE session to avoid mixing languages in existing dialogs. */
final class I18n {
  static final String KEY="corp.jira.language";
  static final String LANGUAGE=initial();
  static final Map<String,String> EN=loadEnglish();
  static String fromLocale(Locale locale){return "ru".equalsIgnoreCase(locale.getLanguage())?"RU":"EN";}
  static String preference(){
    String test=System.getProperty("corp.jira.language");if("RU".equals(test)||"EN".equals(test))return test;
    if(ApplicationManager.getApplication()!=null){String saved=PropertiesComponent.getInstance().getValue(KEY);if("RU".equals(saved)||"EN".equals(saved))return saved;}
    try{return fromLocale(DynamicBundle.getLocale());}catch(RuntimeException|LinkageError e){return fromLocale(Locale.getDefault());}
  }
  static String initial(){return preference();}
  static Map<String,String> loadEnglish(){try(InputStream in=I18n.class.getResourceAsStream("/i18n/en.json")){if(in==null)throw new IllegalStateException("Missing English UI bundle");JsonObject object=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();Map<String,String> result=new HashMap<>();object.entrySet().forEach(e->result.put(e.getKey(),e.getValue().getAsString()));return Collections.unmodifiableMap(result);}catch(IOException e){throw new ExceptionInInitializerError(e);}}
  static String t(String russian){return "RU".equals(LANGUAGE)?russian:EN.getOrDefault(russian,russian);}
}
