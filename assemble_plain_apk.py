import shutil
import sys
import zipfile

base, dex, out = sys.argv[1:4]
shutil.copy(base, out)
with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
    extra = sys.argv[4:]
    if len(extra) % 2 != 0:
        raise SystemExit("extra files must be SRC DEST pairs")
    for i in range(0, len(extra), 2):
        z.write(extra[i], extra[i + 1])
    names = z.namelist()
print("assembled:", out)
print("entries:", names)
