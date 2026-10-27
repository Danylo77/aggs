from docx import Document
from pathlib import Path
from lxml import etree
p=Path('Практика_Звіт_Теплий.docx'); d=Document(p)
print('HEADINGS:')
for i,p in enumerate(d.paragraphs):
 if p.style.name.startswith('Heading') or p.text.strip().upper() in {'ЗМІСТ','ВСТУП','ВИСНОВКИ','СПИСОК ВИКОРИСТАНИХ ДЖЕРЕЛ'}:
  print(i,repr(p.style.name),repr(p.text.strip()),'align',p.alignment,'outline',p.paragraph_format.keep_with_next)
print('TAIL:')
for i,p in list(enumerate(d.paragraphs))[-50:]:
 if p.text.strip(): print(i,repr(p.style.name),repr(' '.join(p.text.split())[:400]))
print('STYLES:')
for name in ['Normal','Normal (Web)','Heading 1','Heading 2']:
 s=d.styles[name]; f=s.font; pf=s.paragraph_format
 print(name,'font',f.name,f.size.pt if f.size else None,'bold',f.bold,'italic',f.italic,'align',pf.alignment,'first',pf.first_line_indent,'left',pf.left_indent,'before',pf.space_before,'after',pf.space_after,'line',pf.line_spacing,'keep',pf.keep_with_next,'break',pf.page_break_before)
print('CORE',d.core_properties.title,d.core_properties.subject,d.core_properties.author)
print('SETTINGS updateFields=',bool(d.settings.element.xpath('.//w:updateFields')))
print('TOC fields=',len(d.element.body.xpath('.//w:instrText[contains(text(),"TOC")]')),'PAGE fields=',len(d.element.body.xpath('.//w:instrText[contains(text(),"PAGE")]')))
