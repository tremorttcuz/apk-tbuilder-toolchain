import zipfile,sys
# usage: merge-jar.py out.jar dex.jar src1.jar [src2.jar ...] [--markers]
# копирует dex и все не-.class файлы; с --markers кладёт пустые файлы-классы для Kotlin (PathUtil и пакет cli/jvm/compiler)
out=zipfile.ZipFile(sys.argv[1],'w',zipfile.ZIP_DEFLATED)
names=set()
d=zipfile.ZipFile(sys.argv[2])
for n in d.namelist(): out.writestr(n,d.read(n)); names.add(n)
markers = '--markers' in sys.argv
for s in [a for a in sys.argv[3:] if a != '--markers']:
    z=zipfile.ZipFile(s)
    for n in z.namelist():
        if n.endswith('/') or n in names or n.startswith('META-INF/MANIFEST') or n.endswith('.SF') or n.endswith('.RSA') or n.endswith('.DSA'): continue
        if n.endswith('.class'): 
            # пустые заглушки: ядру Kotlin нужно, чтобы ресурс класса находился (определяет путь jar)
            if markers and (n == 'org/jetbrains/kotlin/utils/PathUtil.class' or n.startswith('org/jetbrains/kotlin/cli/jvm/compiler/')):
                out.writestr(n,b''); names.add(n)
            continue
        out.writestr(n,z.read(n)); names.add(n)
out.close(); print(len(names))
