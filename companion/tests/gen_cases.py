import json, random
import sys; random.seed(int(sys.argv[1]))
words = "the court said that review was rare and the judges in Norway were careful with power though the Storting often listened before it passed laws".split()
extra = ["Mr.","Dr.","«Høyesterett»","café","naïve","ﬁnal","émigré","U.S.","1814,","(see","p.","12)","“quoted”","—","…","😀word","x y","Ã¦","straße","İstanbul"]
def sentence():
    n=random.randint(3,18)
    ws=[random.choice(words+extra) for _ in range(n)]
    ws[0]=ws[0].capitalize()
    return " ".join(ws)+random.choice([".", "!", "?", "…", ".”", ".)"])
cases=[]
for case in range(6):
    kind = 'pdf' if case%3==2 else 'epub'
    units=[]
    alltoks=[]
    for c in range(random.randint(3,8)):
        para=" ".join(sentence() for _ in range(random.randint(1,5)))
        if kind=='pdf':
            ws=para.split()
            units.append({"page":c+1,"words":ws}); alltoks+=ws
        else:
            units.append({"c":c//3,"p":c,"h":"" if c%2 else "h1","text":para}); alltoks+=para.split()
    # transcript: drop/insert words, times
    segs=[]; t=0; cur=[]
    for w in alltoks:
        r=random.random()
        if r<0.25: continue
        if r<0.35: cur.append(random.choice(words))
        cur.append(w.strip('.,!?…“”«»()'))
        if len(cur)>=random.randint(6,14):
            dur=len(cur)*random.randint(250,450); segs.append({"s":t,"e":t+dur,"t":" ".join(cur)}); t+=dur+random.randint(0,800); cur=[]
    if cur: segs.append({"s":t,"e":t+len(cur)*300,"t":" ".join(cur)})
    if case==5: segs=segs[:len(segs)//2]+[{"s":999999,"e":1000500,"t":"music [Music]"}]+segs[len(segs)//2:]
    cases.append({"text":{"kind":kind,"units":units},"segments":segs})
json.dump(cases,open('cases.json','w',encoding='utf-8'),ensure_ascii=False)
