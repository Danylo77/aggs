from pathlib import Path
import csv, math, sys
csv.field_size_limit(sys.maxsize)
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from shapely import wkt
from shapely.geometry import Polygon, MultiPolygon

ROOT=Path('.')
OUT=ROOT/'photos'; OUT.mkdir(exist_ok=True)
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':9})

SPECS=[
 ('6.2','record-00016-aae85b5057cf',40.0,ROOT/'benchmark-results','Спрощення нерівномірно дискретизованої залізничної лінії',False),
 ('6.3','record-00027-77f43f50ff9f',80.0,ROOT/'target/report-photo-results','Агресивне спрощення короткої залізничної лінії',True),
 ('6.4','record-00051-57bddba92499',40.0,ROOT/'benchmark-results','Збереження локальної форми короткого водного шляху',False),
 ('6.5','record-00005-7950f78f4ff0',40.0,ROOT/'benchmark-results','Змішаний результат спрощення полігональної межі',False),
 ('6.6','record-00052-9c3c443c3a59',60.0,ROOT/'target/report-photo-results','Випадок переваги глобального допуску Douglas–Peucker',True),
]

def rows(path):
 with path.open(newline='',encoding='utf-8') as f:return list(csv.DictReader(f))
def find(rows,id,target,algorithm=None):
 for r in rows:
  if r['input_id']==id and abs(float(r['target_reduction_percent'])-target)<1e-9 and (algorithm is None or r.get('algorithm')==algorithm):return r
 raise KeyError((id,target,algorithm))
def seqs(g):
 if g.geom_type=='LineString': return [list(g.coords)]
 if g.geom_type=='MultiLineString': return [list(x.coords) for x in g.geoms]
 if g.geom_type=='Polygon': return [list(g.exterior.coords)]+[list(x.coords) for x in g.interiors]
 if g.geom_type=='MultiPolygon':
  return sum(([list(p.exterior.coords)]+[list(x.coords) for x in p.interiors] for p in g.geoms),[])
 return []
def key(p):return round(p[0],12),round(p[1],12)
def removed(original,result):
 result_counts={}
 for part in seqs(result):
  for p in part:result_counts[key(p)]=result_counts.get(key(p),0)+1
 ans=[]
 for part in seqs(original):
  for p in part:
   k=key(p)
   if result_counts.get(k,0):result_counts[k]-=1
   else:ans.append(p)
 return ans
def draw_lines(ax,g,color,lw=2,alpha=1,z=2):
 for part in seqs(g):
  if not part:continue
  ax.plot([p[0] for p in part],[p[1] for p in part],color=color,lw=lw,alpha=alpha,zorder=z)
def draw_points(ax,g,color,size=18,z=4):
 pts=[p for part in seqs(g) for p in part]
 if pts:ax.scatter([p[0] for p in pts],[p[1] for p in pts],s=size,color=color,edgecolor='white',linewidth=.35,zorder=z)
def fill_polygon(ax,g,color,alpha):
 polys=[g] if g.geom_type=='Polygon' else list(g.geoms) if g.geom_type=='MultiPolygon' else []
 for p in polys:
  x,y=p.exterior.xy; ax.fill(x,y,color=color,alpha=alpha,zorder=0)
  for ring in p.interiors:
   x,y=ring.xy; ax.fill(x,y,color='white',zorder=1)
def max_deviation(original,result):
 if original.geom_type!='LineString' or result.geom_type!='LineString':return None
 src=list(original.coords); out=list(result.coords); idx=[]; pos=0
 for q in out:
  found=None
  for i in range(pos,len(src)):
   if key(src[i])==key(q):found=i;break
  if found is None:return None
  idx.append(found);pos=found+1
 best=None
 for a,b in zip(idx,idx[1:]):
  x1,y1=src[a][:2];x2,y2=src[b][:2];dx=x2-x1;dy=y2-y1;den=dx*dx+dy*dy
  for i in range(a+1,b):
   px,py=src[i][:2];t=max(0,min(1,((px-x1)*dx+(py-y1)*dy)/den)) if den else 0
   proj=(x1+t*dx,y1+t*dy);d=math.hypot(px-proj[0],py-proj[1])
   if best is None or d>best[0]:best=(d,(px,py),proj)
 return best
def fmt(x):
 x=float(x);return f'{x:.3e}' if x and (abs(x)<1e-3 or abs(x)>=1e4) else f'{x:.6f}'

def generate(number,id,target,folder,caption,highlight):
 gs=rows(folder/'geometries.csv'); ms=rows(folder/'algorithm_metrics.csv')
 gr=find(gs,id,target); am=find(ms,id,target,'adaptive'); dm=find(ms,id,target,'douglas_peucker')
 assert gr['matched'].lower()=='true' and gr['adaptive_fallback_to_original'].lower()!='true'
 original=wkt.loads(gr['original_wkt']); adaptive=wkt.loads(gr['adaptive_wkt']); dp=wkt.loads(gr['douglas_peucker_wkt'])
 fig,axes=plt.subplots(1,3,figsize=(15,5.4),sharex=True,sharey=True)
 colors={'original':'#5f6368','adaptive':'#1877b4','dp':'#e27622','removed':'#c23b3b'}
 panels=[('Оригінал',original,colors['original'],None),('Adaptive',adaptive,colors['adaptive'],am),('Douglas–Peucker',dp,colors['dp'],dm)]
 for ax,(title,g,color,m) in zip(axes,panels):
  if g.geom_type in ('Polygon','MultiPolygon'):fill_polygon(ax,g,color,.10)
  if title!='Оригінал':draw_lines(ax,original,'#b8bdc3',1.0,.65,1)
  draw_lines(ax,g,color,2.0,1,3);draw_points(ax,g,color,18,4)
  if title!='Оригінал':
   rem=removed(original,g)
   if rem:ax.scatter([p[0] for p in rem],[p[1] for p in rem],marker='x',s=16,color=colors['removed'],alpha=.65,zorder=2)
  subtitle=f'N = {len([p for s in seqs(g) for p in s])}'
  if m:subtitle+=f"; скорочення = {float(m['actual_reduction_percent']):.2f}%"
  ax.set_title(title+'\n'+subtitle,fontsize=10.5,weight='bold')
  ax.set_aspect('equal',adjustable='box');ax.grid(alpha=.2);ax.ticklabel_format(useOffset=False)
  ax.tick_params(labelsize=7);ax.set_xlabel('довгота / X')
 axes[0].set_ylabel('широта / Y')
 if original.geom_type in ('Polygon','MultiPolygon'):
  for ax,g in ((axes[1],adaptive),(axes[2],dp)):
   diff=original.symmetric_difference(g)
   if not diff.is_empty:fill_polygon(ax,diff,'#d94f4f',.22)
 if highlight:
  target_geom=adaptive if number=='6.6' else adaptive
  best=max_deviation(original,target_geom)
  if best:
   d,p,q=best;axes[1].plot([p[0],q[0]],[p[1],q[1]],ls='--',lw=1.5,color='#7d1d1d',zorder=6)
   axes[1].scatter([p[0]],[p[1]],marker='*',s=90,color='#7d1d1d',zorder=7)
   axes[1].annotate(f'макс. відхилення\n{fmt(d)}',xy=p,xytext=(8,12),textcoords='offset points',fontsize=7,color='#7d1d1d',
                    arrowprops=dict(arrowstyle='->',color='#7d1d1d',lw=.8))
 fig.suptitle(f'Рисунок {number}. {id}; цільове скорочення {target:.0f}%\n{caption}',fontsize=13,weight='bold',y=.99)
 metrics=(f"Hausdorff: Adaptive {fmt(am['symmetric_hausdorff_distance'])}; DP {fmt(dm['symmetric_hausdorff_distance'])}    |    "
          f"Mean deviation: Adaptive {fmt(am['mean_geometric_deviation'])}; DP {fmt(dm['mean_geometric_deviation'])}")
 if original.geom_type in ('Polygon','MultiPolygon'):
  metrics+=f"\nSymmetric difference area: Adaptive {fmt(am['symmetric_difference_area'])}; DP {fmt(dm['symmetric_difference_area'])}"
 fig.text(.5,.03,metrics,ha='center',fontsize=8.5)
 handles=[Line2D([0],[0],color='#b8bdc3',lw=1,label='оригінальний контур у фонових панелях'),
          Line2D([0],[0],marker='o',color='w',markerfacecolor='#1877b4',markersize=6,label='збережені вершини'),
          Line2D([0],[0],marker='x',color='#c23b3b',lw=0,markersize=6,label='вилучені вершини')]
 fig.legend(handles=handles,loc='lower center',bbox_to_anchor=(.5,.065),ncol=3,frameon=False,fontsize=8)
 fig.tight_layout(rect=[0,.13,1,.90])
 path=OUT/f'figure_{number.replace(".","_")}_{id}_target_{int(target)}.png'
 fig.savefig(path,dpi=260,bbox_inches='tight');plt.close(fig)
 print(path,am['actual_reduction_percent'],dm['actual_reduction_percent'])

for spec in SPECS:generate(*spec)
