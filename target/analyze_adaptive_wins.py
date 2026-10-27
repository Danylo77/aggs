import csv, hashlib, math, re, statistics
from pathlib import Path
from shapely import wkt as swkt

root=Path('.')
lines=(root/'logs.txt').read_text(encoding='utf-8').splitlines()
wkts=[x.strip() for x in (root/'wkt_examples.txt').read_text(encoding='utf-8').splitlines() if x.strip()]
blocks=[]; cur=None
for line in lines:
    if line.startswith('Target reduction:'):
        cur={'target':float(re.search(r'([0-9.]+)%',line).group(1))}; blocks.append(cur)
    elif cur is not None and line.startswith('Reduction matched:'):
        cur['matched']=line.endswith('true')
    elif cur is not None:
        for label,key,pct in [('Actual reduction (%)','reduction',True),('Hausdorff distance','hausdorff',False),('Mean geometric deviation','mean',False)]:
            if line.startswith(label):
                vals=line[len(label):].split()
                cur[key]=(float(vals[0].rstrip('%')),float(vals[1].rstrip('%')))

def sid(i,s): return f'record-{i+1:05d}-'+hashlib.sha256(s.encode()).hexdigest()[:12]
def source(i):
    n=i+1
    if n<=15:return 'Lviv city areas'
    if n<=30:return 'Lviv railway lines'
    if n<=45:return 'Venice island areas'
    if n<=60:return 'Amsterdam waterways'
    return 'Synthesized multipart'

def seqs(g):
    if g.geom_type=='LineString': return [list(g.coords)]
    if g.geom_type=='MultiLineString': return [list(x.coords) for x in g.geoms]
    if g.geom_type=='Polygon': return [list(g.exterior.coords)]+[list(r.coords) for r in g.interiors]
    if g.geom_type=='MultiPolygon': return sum(([list(p.exterior.coords)]+[list(r.coords) for r in p.interiors] for p in g.geoms),[])
    return []
def desc(s):
    g=swkt.loads(s); ss=seqs(g); lens=[]; turns=[]
    for pts in ss:
        for a,b in zip(pts,pts[1:]): lens.append(math.hypot(b[0]-a[0],b[1]-a[1]))
        closed=len(pts)>2 and pts[0][:2]==pts[-1][:2]
        core=pts[:-1] if closed else pts
        inds=range(len(core)) if closed else range(1,len(core)-1)
        for j in inds:
            p=core[(j-1)%len(core)]; q=core[j]; r=core[(j+1)%len(core)]
            u=(q[0]-p[0],q[1]-p[1]); v=(r[0]-q[0],r[1]-q[1]); nu=math.hypot(*u); nv=math.hypot(*v)
            if nu and nv: turns.append(math.degrees(math.acos(max(-1,min(1,(u[0]*v[0]+u[1]*v[1])/(nu*nv))))))
    pos=[x for x in lens if x>0]; mean=statistics.mean(pos)
    minx,miny,maxx,maxy=g.bounds; w=maxx-minx; h=maxy-miny
    return {'type':g.geom_type,'vertices':sum(len(x) for x in ss),'seg_cv':statistics.pstdev(pos)/mean if mean else 0,
      'seg_ratio':max(pos)/min(pos) if pos else 0,'turn_p90':sorted(turns)[int(.9*(len(turns)-1))] if turns else 0,
      'turn_max':max(turns) if turns else 0,'turn_gt45':sum(x>45 for x in turns)/len(turns) if turns else 0,
      'aspect':max(w,h)/min(w,h) if min(w,h)>0 else float('inf')}

wins=[]; dpwins=[]
for bi,b in enumerate(blocks):
    i=bi//4; b.update(index=i,id=sid(i,wkts[i]),source=source(i),**desc(wkts[i]))
    if not b.get('matched'): continue
    ah,dh=b['hausdorff']; am,dm=b['mean']
    if ah<dh and am<dm:
        gh=(dh-ah)/dh; gm=(dm-am)/dm; b.update(h_gain=gh,m_gain=gm,score=(gh+gm)/2,min_gain=min(gh,gm)); wins.append(b)
    elif ah>dh and am>dm: dpwins.append(b)
wins.sort(key=lambda x:(-x['score'],x['id'],x['target']))
print('WINNERS',len(wins))
for rank,b in enumerate(wins,1):
 print(f"{rank:2d}|{b['id']}|line={b['index']+1}|{b['source']}|{b['type']}|target={b['target']:.0f}|actual={b['reduction'][0]:.4f}/{b['reduction'][1]:.4f}|H={b['hausdorff'][0]:.9g}/{b['hausdorff'][1]:.9g}|M={b['mean'][0]:.9g}/{b['mean'][1]:.9g}|gains={100*b['h_gain']:.2f}/{100*b['m_gain']:.2f}|score={100*b['score']:.2f}|N={b['vertices']}|cv={b['seg_cv']:.2f}|ratio={b['seg_ratio']:.1f}|p90turn={b['turn_p90']:.1f}|sharp={100*b['turn_gt45']:.1f}|aspect={b['aspect']:.1f}")
print('GROUPS')
for name,arr in [('adaptive',wins),('dp',dpwins)]:
 for typ in ['LineString','Polygon']:
  x=[b for b in arr if b['type']==typ]
  if x: print(name,typ,len(x),'cv',statistics.mean(b['seg_cv'] for b in x),'ratio',statistics.mean(b['seg_ratio'] for b in x),'p90turn',statistics.mean(b['turn_p90'] for b in x),'sharp',statistics.mean(b['turn_gt45'] for b in x),'aspect',statistics.mean(b['aspect'] for b in x))
# Existing exact output availability
p=root/'benchmark-results'/'geometries.csv'
if p.exists():
 rows={(r['input_id'],float(r['target_reduction_percent'])):r for r in csv.DictReader(p.open(encoding='utf-8'))}
 print('AVAILABLE_40')
 for b in wins:
  if b['target']==40:
   r=rows.get((b['id'],40.0)); print(b['id'], bool(r and r.get('adaptive_wkt') and r.get('douglas_peucker_wkt')))

for b in dpwins:
    ah,dh=b['hausdorff']; am,dm=b['mean']
    b['dp_score']=((ah-dh)/ah+(am-dm)/am)/2 if ah and am else 0
print('TOP_DP_WINNERS')
for b in sorted(dpwins,key=lambda x:-x['dp_score'])[:8]:
 print(f"{b['id']}|line={b['index']+1}|{b['source']}|{b['type']}|target={b['target']:.0f}|score={100*b['dp_score']:.2f}|N={b['vertices']}|cv={b['seg_cv']:.2f}|ratio={b['seg_ratio']:.1f}|p90turn={b['turn_p90']:.1f}|sharp={100*b['turn_gt45']:.1f}|aspect={b['aspect']:.1f}")