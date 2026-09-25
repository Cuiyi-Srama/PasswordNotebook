#!/usr/bin/env python3
"""Repack a linked APK with classes.dex, keeping resources.arsc STORED.

Android 11+ (targetSdk >= 30) refuses to install an APK whose resources.arsc
is compressed: "Failed parse during installPackageLI: Targeting R+ requires the
resources.arsc of installed APKs to be stored uncompressed and aligned on a
4-byte boundary". A plain `zip -r` compresses it, so the archive must be built
explicitly with ZIP_STORED for that one entry, then zipalign -p 4 aligned.
"""
import zipfile, sys, os

def main(src, dex, out):
    z = zipfile.ZipFile(src)
    items = [(i.filename, z.read(i.filename))
             for i in z.infolist() if not i.filename.startswith('META-INF/')]
    z.close()
    items.append(('classes.dex', open(dex, 'rb').read()))

    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zf:
        for name, data in items:
            zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = (zipfile.ZIP_STORED
                                if name == 'resources.arsc'
                                else zipfile.ZIP_DEFLATED)
            zi.external_attr = 0o644 << 16
            zf.writestr(zi, data)
    print('repacked ->', out, os.path.getsize(out), 'bytes')

if __name__ == '__main__':
    main(*sys.argv[1:4])
