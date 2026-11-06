import json,os,shutil,sys
work,repo,app=sys.argv[1:4]
fonts=json.load(open(os.path.join(work,'fonts.json')))
EXCLUDE={'NV Adelph'}
assets=os.path.join(app,'app/src/main/assets/fonts'); os.makedirs(assets,exist_ok=True)
src_root=os.path.join(repo,'fonts')
families=[]
for name,v in fonts.items():
    if name in EXCLUDE: continue
    faces=[]
    for f in sorted(v['files'],key=lambda x:(x['italic'],x['weight'])):
        shutil.copy(os.path.join(src_root,v['collection'],f['file']),os.path.join(assets,f['file']))
        faces.append(f)
    families.append((name,v,faces))
families.sort(key=lambda x:(x[1]['collection']!='core',x[0].lower()))
def q(s): return '"'+s.replace('\\','\\\\').replace('"','\\"')+'"'
lines=['package com.tim.reader.theme','','// Made from the font files themselves. Rerun the script rather than editing by hand.','','val BOOK_FONT_LIST = listOf(']
for name,v,faces in families:
    lines.append(f'    BookFont(')
    lines.append(f'        name = {q(name)},')
    lines.append(f'        isCore = {"true" if v["collection"]=="core" else "false"},')
    lines.append(f'        faces = listOf(')
    for f in faces:
        lines.append(f'            BookFontFace({q(f["file"])}, {f["weight"]}, {"true" if f["italic"] else "false"}),')
    lines.append('        ),')
    lines.append('    ),')
lines.append(')')
open(os.path.join(app,'app/src/main/java/com/tim/reader/theme/BookFontList.kt'),'w').write('\n'.join(lines)+'\n')
# Licenses
ofl=open(os.path.join(work,'src/atkinson-hyperlegible-next/OFL.txt')).read().split('\n',1)[1].lstrip('\n')
bitstream=open(os.path.join(work,'src/cartisse/LICENSE')).read()
notes={
 'Cartisse':'Licensed under the Bitstream Charter license, reproduced at the end of this file.',
 'NV Palatium':'Based on Domitian, which is offered under the SIL Open Font License 1.1, the GNU AGPL with a font exception, or the LaTeX Project Public License. Quartz Reader uses it under the SIL Open Font License 1.1.',
 'NV Legible Next':'Based on Atkinson Hyperlegible Next, licensed under the SIL Open Font License 1.1 (https://github.com/googlefonts/atkinson-hyperlegible-next).',
 'NV Clara':'Based on Clara by Séamas Ó Brógáin, licensed under the SIL Open Font License 1.1.',
}
out=['FONT LICENSES','','These fonts come from the ebook-fonts collection by Nico Verbruggen,','https://github.com/nicoverbruggen/ebook-fonts, which adjusts open fonts for reading on screens.','Each font keeps its original copyright and license, listed below.','']
for name,v,faces in families:
    out.append('='*60); out.append(name); out.append('Files: '+', '.join(f['file'] for f in faces)); out.append('')
    for c in v['copyright']: out.append(c.strip())
    out.append('')
    out.append(notes.get(name,'Licensed under the SIL Open Font License, Version 1.1, reproduced at the end of this file.'))
    out.append('')
out+=['='*60,'SIL OPEN FONT LICENSE, VERSION 1.1','',ofl.strip(),'','='*60,'BITSTREAM CHARTER LICENSE (Cartisse)','',bitstream.strip(),'']
open(os.path.join(app,'app/src/main/assets/font-licenses.txt'),'w').write('\n'.join(out))
print(len(families),'families', sum(len(f) for _,_,f in families),'files')
