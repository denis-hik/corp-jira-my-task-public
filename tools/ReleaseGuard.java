import org.jetbrains.org.objectweb.asm.*;
import org.jetbrains.org.objectweb.asm.commons.*;
import java.nio.file.*;
import java.util.*;

/** Conservative remapping: IDE entry points and all non-private methods stay stable. */
public final class ReleaseGuard {
  public static void main(String[] args)throws Exception {
    Path input=Path.of(args[0]),output=Path.of(args[1]),mapFile=Path.of(args[2]);
    List<Path> files;try(var paths=Files.walk(input)){files=paths.filter(p->p.toString().endsWith(".class")).sorted().toList();}
    Map<String,String> classes=new LinkedHashMap<>(),methods=new LinkedHashMap<>(),fields=new LinkedHashMap<>();
    if(args.length>3){for(String line:Files.readAllLines(mapFile)){String[] p=line.split("\t");switch(p[0]){case "C"->classes.put(p[1],p[2]);case "M"->methods.put(p[1]+"\t"+p[2]+"\t"+p[3],p[4]);case "F"->fields.put(p[1]+"\t"+p[2]+"\t"+p[3],p[4]);}}}
    else {
      Set<String> keep=Set.of("local/corp/jira/JiraToolWindowFactory","local/corp/jira/JiraSettings");
      int[] counter={0};
      for(Path file:files){ClassReader reader=new ClassReader(Files.readAllBytes(file));String owner=reader.getClassName();if(!keep.contains(owner))classes.put(owner,"local/corp/jira/c"+(counter[0]++));
        boolean record="java/lang/Record".equals(reader.getSuperName());
        reader.accept(new ClassVisitor(Opcodes.ASM9){
          public FieldVisitor visitField(int access,String name,String desc,String signature,Object value){if(!record&&(access&Opcodes.ACC_PRIVATE)!=0&&!name.equals("serialVersionUID"))fields.put(owner+"\t"+name+"\t"+desc,"f"+counter[0]++);return null;}
          public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions){if((access&Opcodes.ACC_PRIVATE)!=0&&!name.startsWith("<")&&!Set.of("readObject","writeObject","readResolve","writeReplace","readObjectNoData").contains(name))methods.put(owner+"\t"+name+"\t"+desc,"m"+counter[0]++);return null;}
        },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
      }
      StringBuilder map=new StringBuilder();classes.forEach((k,v)->map.append("C\t").append(k).append('\t').append(v).append('\n'));fields.forEach((k,v)->map.append("F\t").append(k).append('\t').append(v).append('\n'));methods.forEach((k,v)->map.append("M\t").append(k).append('\t').append(v).append('\n'));Files.writeString(mapFile,map);
    }
    Remapper remapper=new Remapper(){public String map(String name){return classes.getOrDefault(name,name);}public String mapFieldName(String owner,String name,String descriptor){return fields.getOrDefault(owner+"\t"+name+"\t"+descriptor,name);}public String mapMethodName(String owner,String name,String descriptor){return methods.getOrDefault(owner+"\t"+name+"\t"+descriptor,name);}};
    Files.createDirectories(output);
    for(Path file:files){ClassReader reader=new ClassReader(Files.readAllBytes(file));ClassWriter writer=new ClassWriter(0);reader.accept(new ClassRemapper(writer,remapper),ClassReader.SKIP_DEBUG);Path target=output.resolve(remapper.map(reader.getClassName())+".class");Files.createDirectories(target.getParent());Files.write(target,writer.toByteArray());}
    System.out.println("ReleaseGuard: "+files.size()+" classes; "+classes.size()+" class names, "+fields.size()+" private fields, "+methods.size()+" private methods remapped; debug metadata removed.");
  }
}
