import os,sys,json,collections
from fontTools.ttLib import TTFont
root=sys.argv[1]
out=collections.OrderedDict()
for col in ['core','extra']:
    for f in sorted(os.listdir(os.path.join(root,col))):
        if not f.endswith('.ttf'): continue
        t=TTFont(os.path.join(root,col,f),lazy=True)
        n=t['name']
        g=lambda i:(n.getDebugName(i) or '').strip()
        fam=g(16) or g(1); sub=g(17) or g(2)
        os2=t['OS/2']
        e=out.setdefault(fam,{'collection':col,'files':[],'copyright':set(),'license':set(),'url':set()})
        e['files'].append({'file':f,'sub':sub,'weight':os2.usWeightClass,'italic':bool(os2.fsSelection&1)})
        e['copyright'].add(g(0)); e['license'].add(g(13)); e['url'].add(g(14))
for k,v in out.items():
    for s in ('copyright','license','url'): v[s]=sorted(x for x in v[s] if x)
json.dump(out,open(sys.argv[2],'w'),indent=1)
for k,v in out.items():
    lic=' | '.join(v['license'])[:90].replace('\n',' ')
    print(f"{v['collection']:5} {k:24} {len(v['files'])} {[ (x['sub'],x['weight']) for x in v['files']]} :: {lic}")
