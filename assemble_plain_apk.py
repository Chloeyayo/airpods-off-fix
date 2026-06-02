import shutil
import sys
import zipfile

base, dex, out = sys.argv[1:4]
shutil.copy(base, out)
with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
    names = z.namelist()
print("assembled:", out)
print("entries:", names)
