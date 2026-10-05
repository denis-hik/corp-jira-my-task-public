from pathlib import Path
import os,subprocess,zipfile,shutil
root=Path(__file__).resolve().parent
ide=Path(os.environ.get('WEBSTORM_HOME','/Applications/WebStorm.app/Contents'))
java=ide/'jbr/Contents/Home/bin/javac'
libs=list((ide/'plugins/vcs-git').rglob('*.jar'))+list((ide/'lib').glob('*.jar'))+list((ide/'lib/modules').glob('*.jar'))+list((ide/'plugins/jcef-plugin/lib').glob('*.jar'))+list((ide/'plugins/jcef-plugin/lib/modules').glob('*.jar'))
build=root/'build';classes=build/'classes'
if classes.exists():shutil.rmtree(classes)
classes.mkdir(parents=True,exist_ok=True)
subprocess.run([str(java),'-encoding','UTF-8','--release','21','-classpath',os.pathsep.join(map(str,libs)),'-d',str(classes),*map(str,(root/'src').rglob('*.java'))],check=True)
guard=build/'guard';guard.mkdir(exist_ok=True)
release=build/'release-classes'
if release.exists():shutil.rmtree(release)
asm=next((f for f in [ide/'lib/intellij.libraries.asm.jar',ide/'lib/module-intellij.libraries.asm.jar'] if f.is_file()),None)
if asm is None:raise RuntimeError('ASM library not found in WebStorm SDK')
subprocess.run([str(java),'-encoding','UTF-8','-classpath',str(asm),'-d',str(guard),str(root/'tools/ReleaseGuard.java')],check=True)
subprocess.run([str(ide/'jbr/Contents/Home/bin/java'),'-classpath',os.pathsep.join([str(guard),str(asm)]),'ReleaseGuard',str(classes),str(release),str(build/'obfuscation-map.tsv')],check=True)
jar=build/'corp-jira-my-tasks.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
 for folder in [release,root/'resources']:
  for p in folder.rglob('*'):
   if p.is_file():z.write(p,str(p.relative_to(folder)))
(root/'dist').mkdir(exist_ok=True)
with zipfile.ZipFile(root/'dist/corp-jira-my-task-2.24.13.zip','w',zipfile.ZIP_DEFLATED) as z:z.write(jar,'corp-jira-my-tasks/lib/'+jar.name)
print('Plugin built:',root/'dist/corp-jira-my-task-2.24.13.zip')
