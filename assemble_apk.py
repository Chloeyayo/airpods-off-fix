import sys, shutil, zipfile
base, dex, xinit, xscope, out = sys.argv[1:6]
shutil.copy(base, out)
with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
    z.write(xinit, "assets/xposed_init")
    z.write(xscope, "assets/xposed_scope")
    z.write(xscope, "META-INF/xposed/scope.list")
    names = z.namelist()
print("assembled:", out)
print("entries:", names)
