package com.bubbleladder.mhsunweightedv1;

import java.util.*;

/**
 * AUTO-RANGE80 메인엔진
 * - 4개 조합 중 1개를 제외하고 나머지 3개를 추천하는 삼치기 엔진
 * - 학습길이 12회 ~ 현재 확보 전체구간을 자동 탐색
 * - pre / holdout 분리, 전반/후반 안정성 검사
 * - 80% 통과 후보가 있으면 우선, 없으면 가장 안정적인 최고 1픽을 강제로 출력
 */
public final class TriPick80Engine {
    private TriPick80Engine(){}

    public static final double TARGET = 0.80;
    private static final double PRE_BLOCK_FLOOR = 0.75;
    private static final int WARMUP = 8;
    private static final int MIN_PRE = 10;
    private static final int MIN_HOLD = 6;

    public static final class Result {
        public boolean certified;
        public boolean targetAchieved;
        public int excludedCombo=0;
        public int validationN=0, validationHit=0, preN=0, preHit=0, holdN=0, holdHit=0, searched=0;
        public int selectedWindow=0;
        public double validationRate=0.0, preRate=0.0, holdRate=0.0, firstRate=0.0, secondRate=0.0;
        public double bestObservedRate=0.0;
        public String mode="-", rule="-", detail="-", picksLabel="-", excludedLabel="-";
    }

    private interface Excluder { int exclude(List<FlowCore.Result> a,int end); }
    private static final class Strategy {
        final String family,name; final Excluder x;
        Strategy(String family,String name,Excluder x){this.family=family;this.name=name;this.x=x;}
    }
    private static final class Eval {
        int n,h,n1,h1,n2,h2,excluded;
        double rate(){return n==0?0:(double)h/n;}
        double r1(){return n1==0?0:(double)h1/n1;}
        double r2(){return n2==0?0:(double)h2/n2;}
        double floor(){return Math.min(r1(),r2());}
    }
    private static final class Candidate {
        final int i,j,k; final Eval pre; final List<Strategy> ss;
        Candidate(int i,int j,int k,Eval pre,List<Strategy> ss){this.i=i;this.j=j;this.k=k;this.pre=pre;this.ss=ss;}
        boolean single(){return j<0;}
        String rule(){
            if(single())return ss.get(i).name;
            return ss.get(i).name+" + "+ss.get(j).name+" + "+ss.get(k).name;
        }
    }
    private static final class WindowChoice {
        final int window; final Result result;
        WindowChoice(int window,Result result){this.window=window;this.result=result;}
    }

    public static Result optimize(List<FlowCore.Result> all){
        Result out=new Result();
        if(all==null||all.isEmpty()){out.detail="AUTO-RANGE80 데이터 준비중";return out;}
        int total=all.size();
        if(total < WARMUP + MIN_HOLD){
            Result small = optimizeWindow(all, total);
            small.detail = "AUTO-RANGE80 초기표본 " + total + "회 · " + small.detail;
            return small;
        }

        List<Integer> windows=candidateWindows(total);
        WindowChoice bestTarget=null, bestObserved=null;
        for(int w:windows){
            List<FlowCore.Result> view=new ArrayList<>(all.subList(total-w,total));
            Result r=optimizeWindow(view,w);
            bestObserved=betterObserved(bestObserved,new WindowChoice(w,r));
            if(r.targetAchieved)bestTarget=betterTarget(bestTarget,new WindowChoice(w,r));
        }
        WindowChoice chosen = bestTarget!=null ? bestTarget : bestObserved;
        if(chosen==null){
            int ex=bootstrapExclude(all,total); Eval boot=bootstrapEval(all,total);
            out.certified=true;out.targetAchieved=false;out.mode="🎯 AUTO-RANGE80 강제 1픽";
            out.excludedCombo=ex;out.excludedLabel=combo(ex);out.picksLabel=includeLabel(ex);
            out.validationN=boot.n;out.validationHit=boot.h;out.validationRate=boot.rate();
            out.preN=boot.n;out.preHit=boot.h;out.preRate=boot.rate();out.rule="다중창 희소조합";
            out.detail="학습길이 자동탐색 후보가 부족하여 희소조합 기반으로 제외 "+combo(ex)+" 추천";
            return out;
        }
        chosen.result.selectedWindow=chosen.window;
        chosen.result.detail = "선택 학습길이 "+chosen.window+"회\n" + chosen.result.detail;
        return chosen.result;
    }

    private static WindowChoice betterTarget(WindowChoice a,WindowChoice b){
        if(a==null)return b;
        Result ar=a.result, br=b.result;
        double af=scoreFloor(ar), bf=scoreFloor(br);
        if(bf>af+1e-12)return b; if(af>bf+1e-12)return a;
        if(br.validationRate>ar.validationRate+1e-12)return b; if(ar.validationRate>br.validationRate+1e-12)return a;
        // 과도하게 짧은 표본은 불리하게 하고, 비슷하면 더 긴 창을 우선
        if(preferLargerStable(a.window,b.window,ar.validationRate,br.validationRate))return b;
        return a;
    }

    private static WindowChoice betterObserved(WindowChoice a,WindowChoice b){
        if(a==null)return b;
        Result ar=a.result, br=b.result;
        double af=scoreFloor(ar), bf=scoreFloor(br);
        if(bf>af+1e-12)return b; if(af>bf+1e-12)return a;
        if(br.validationRate>ar.validationRate+1e-12)return b; if(ar.validationRate>br.validationRate+1e-12)return a;
        if(preferLargerStable(a.window,b.window,ar.validationRate,br.validationRate))return b;
        return a;
    }

    private static boolean preferLargerStable(int wa,int wb,double ra,double rb){
        if(wb>=24 && wa<24 && rb+1e-12>=ra-0.03)return true;
        if(wa>=24 && wb<24 && ra+1e-12>=rb-0.03)return false;
        return wb>wa;
    }

    private static double scoreFloor(Result r){ return Math.min(r.preRate, r.holdRate); }

    private static List<Integer> candidateWindows(int total){
        LinkedHashSet<Integer> s=new LinkedHashSet<>();
        int max=Math.max(12,total);
        for(int w=12;w<=Math.min(total,30);w++)s.add(w);
        for(int w=32;w<=Math.min(total,60);w+=2)s.add(w);
        for(int w=65;w<=Math.min(total,120);w+=5)s.add(w);
        for(int w=130;w<=Math.min(total,240);w+=10)s.add(w);
        s.add(total);
        if(total<12)s.add(total);
        List<Integer> out=new ArrayList<>();
        for(int w:s)if(w>=Math.min(12,total) && w<=total)out.add(w);
        Collections.sort(out);
        return out;
    }

    private static Result optimizeWindow(List<FlowCore.Result> all,int window){
        Result out=new Result();
        out.selectedWindow=window;
        if(all==null||all.isEmpty()){out.detail="AUTO-RANGE80 데이터 준비중";return out;}
        int end=all.size();

        if(end<WARMUP+MIN_HOLD){
            int ex=bootstrapExclude(all,end);
            Eval boot=bootstrapEval(all,end);
            out.certified=true;out.targetAchieved=boot.n>=MIN_HOLD && boot.rate()+1e-12>=TARGET;
            out.mode=out.targetAchieved?"🔥 초기 80+":"🎯 AUTO-RANGE80 초기 강제 1픽";
            out.excludedCombo=ex;out.excludedLabel=combo(ex);out.picksLabel=includeLabel(ex);
            out.validationN=boot.n;out.validationHit=boot.h;out.validationRate=boot.rate();
            out.preN=boot.n;out.preHit=boot.h;out.preRate=boot.rate();out.holdN=0;out.holdHit=0;out.holdRate=0;
            out.rule="초기 희소조합 다중창 선택";
            out.detail="표본 "+end+"회 · 제외 "+combo(ex)+" · 재현 "+boot.h+"/"+boot.n+" = "+pct(boot.rate());
            return out;
        }

        final int T=Math.max(1, end - WARMUP);
        final int vStart=end-T;
        int holdN=Math.max(MIN_HOLD,T/4);
        if(holdN>=T-MIN_PRE)holdN=Math.max(MIN_HOLD,T/5);
        final int preT=Math.max(1,T-holdN);
        final List<Strategy> ss=strategies(end);

        int[][] exPred=new int[ss.size()][T+1];
        for(int s=0;s<ss.size();s++){
            for(int ti=0;ti<=T;ti++){
                int e=(ti<T)?vStart+ti:end;
                exPred[s][ti]=safeExclude(ss.get(s),all,e);
            }
        }

        Candidate selected80=null,bestObserved=null;
        for(int i=0;i<ss.size();i++){
            out.searched++;
            Eval pre=eval(all,exPred,i,-1,-1,vStart,0,preT,T);
            Candidate c=new Candidate(i,-1,-1,pre,ss);
            bestObserved=betterObserved(bestObserved,c);
            if(qualifiesPre(pre))selected80=betterTarget(selected80,c);
        }
        if(selected80==null){
            for(int i=0;i<ss.size()-2;i++)for(int j=i+1;j<ss.size()-1;j++){
                if(ss.get(i).family.equals(ss.get(j).family))continue;
                for(int k=j+1;k<ss.size();k++){
                    if(ss.get(i).family.equals(ss.get(k).family)||ss.get(j).family.equals(ss.get(k).family))continue;
                    out.searched++;
                    Eval pre=eval(all,exPred,i,j,k,vStart,0,preT,T);
                    Candidate c=new Candidate(i,j,k,pre,ss);
                    bestObserved=betterObserved(bestObserved,c);
                    if(qualifiesPre(pre))selected80=betterTarget(selected80,c);
                }
            }
        }

        if(selected80!=null){
            Eval hold=eval(all,exPred,selected80.i,selected80.j,selected80.k,vStart,preT,T,T);
            Eval overall=eval(all,exPred,selected80.i,selected80.j,selected80.k,vStart,0,T,T);
            boolean achieved=hold.n>=MIN_HOLD && hold.rate()+1e-12>=TARGET && hold.r1()+1e-12>=PRE_BLOCK_FLOOR && hold.r2()+1e-12>=PRE_BLOCK_FLOOR && overall.rate()+1e-12>=TARGET;
            if(achieved){
                fill(out,selected80,hold,overall,true,"🔥 AUTO-RANGE80 80+ 통과");
                return out;
            }
            Candidate force=betterObserved(selected80,bestObserved);
            Eval fhold=eval(all,exPred,force.i,force.j,force.k,vStart,preT,T,T);
            Eval foverall=eval(all,exPred,force.i,force.j,force.k,vStart,0,T,T);
            fill(out,force,fhold,foverall,false,"🎯 AUTO-RANGE80 최고 1픽");
            return out;
        }

        if(bestObserved==null){
            int ex=bootstrapExclude(all,end);Eval boot=bootstrapEval(all,end);
            out.certified=true;out.targetAchieved=false;out.mode="🎯 AUTO-RANGE80 강제 1픽";
            out.excludedCombo=ex;out.excludedLabel=combo(ex);out.picksLabel=includeLabel(ex);
            out.validationN=boot.n;out.validationHit=boot.h;out.validationRate=boot.rate();
            out.preN=boot.n;out.preHit=boot.h;out.preRate=boot.rate();out.rule="다중창 희소조합";
            out.detail="80 후보는 없지만 추천 중단 없이 제외 "+combo(ex)+" · 재현 "+boot.h+"/"+boot.n+" = "+pct(boot.rate());
            return out;
        }
        Eval hold=eval(all,exPred,bestObserved.i,bestObserved.j,bestObserved.k,vStart,preT,T,T);
        Eval overall=eval(all,exPred,bestObserved.i,bestObserved.j,bestObserved.k,vStart,0,T,T);
        fill(out,bestObserved,hold,overall,false,"🎯 AUTO-RANGE80 최고 1픽");
        return out;
    }

    private static void fill(Result out,Candidate c,Eval hold,Eval overall,boolean achieved,String mode){
        out.certified=true;out.targetAchieved=achieved;out.mode=mode;
        out.excludedCombo=overall.excluded;out.excludedLabel=combo(overall.excluded);out.picksLabel=includeLabel(overall.excluded);
        out.validationN=overall.n;out.validationHit=overall.h;out.validationRate=overall.rate();
        out.preN=c.pre.n;out.preHit=c.pre.h;out.preRate=c.pre.rate();
        out.holdN=hold.n;out.holdHit=hold.h;out.holdRate=hold.rate();
        out.firstRate=c.pre.r1();out.secondRate=c.pre.r2();out.bestObservedRate=overall.rate();out.rule=c.rule();
        out.detail=mode+" · 전체 walk-forward "+overall.h+"/"+overall.n+" = "+pct(overall.rate())+
                " · 선택구간 "+c.pre.h+"/"+c.pre.n+" = "+pct(c.pre.rate())+
                " · 마지막 holdout "+hold.h+"/"+hold.n+" = "+pct(hold.rate())+" (전반 "+pct(hold.r1())+" / 후반 "+pct(hold.r2())+")"+
                " · 제외 "+combo(overall.excluded)+" · "+c.rule();
    }

    private static boolean qualifiesPre(Eval e){
        return e.n>=MIN_PRE && e.rate()+1e-12>=TARGET && e.r1()+1e-12>=PRE_BLOCK_FLOOR && e.r2()+1e-12>=PRE_BLOCK_FLOOR && validCombo(e.excluded);
    }
    private static Candidate betterTarget(Candidate a,Candidate b){
        if(a==null)return b;
        double as=Math.abs(a.pre.r1()-a.pre.r2()), bs=Math.abs(b.pre.r1()-b.pre.r2());
        if(bs+1e-12<as)return b;if(as+1e-12<bs)return a;
        double ad=Math.abs(a.pre.rate()-TARGET), bd=Math.abs(b.pre.rate()-TARGET);
        if(bd+1e-12<ad)return b;if(ad+1e-12<bd)return a;
        if(b.single()&&!a.single())return b;if(a.single()&&!b.single())return a;
        if(b.pre.floor()>a.pre.floor()+1e-12)return b;if(a.pre.floor()>b.pre.floor()+1e-12)return a;
        return b.pre.n>a.pre.n?b:a;
    }
    private static Candidate betterObserved(Candidate a,Candidate b){
        if(a==null)return b;
        if(b.pre.floor()>a.pre.floor()+1e-12)return b;if(a.pre.floor()>b.pre.floor()+1e-12)return a;
        if(b.pre.rate()>a.pre.rate()+1e-12)return b;if(a.pre.rate()>b.pre.rate()+1e-12)return a;
        return b.pre.n>a.pre.n?b:a;
    }

    private static Eval eval(List<FlowCore.Result> all,int[][] exPred,int i,int j,int k,int vStart,int from,int to,int currentIndex){
        Eval e=new Eval();int len=Math.max(0,to-from),split=from+Math.max(1,len/2);
        for(int ti=from;ti<to;ti++){
            int ex=ensemble(exPred,i,j,k,ti);
            int actual=all.get(vStart+ti).combo;
            boolean ok=actual!=ex;
            e.n++;if(ok)e.h++;
            if(ti<split){e.n1++;if(ok)e.h1++;}else{e.n2++;if(ok)e.h2++;}
        }
        e.excluded=ensemble(exPred,i,j,k,currentIndex);
        return e;
    }
    private static int ensemble(int[][] p,int i,int j,int k,int t){
        int a=p[i][t];if(j<0)return validCombo(a)?a:4;
        int b=p[j][t],c=p[k][t];
        int[] v=new int[5];if(validCombo(a))v[a]++;if(validCombo(b))v[b]++;if(validCombo(c))v[c]++;
        int best=1,cnt=v[1];for(int x=2;x<=4;x++)if(v[x]>cnt){best=x;cnt=v[x];}
        if(cnt>=2)return best;
        return validCombo(a)?a:4;
    }

    private static int safeExclude(Strategy s,List<FlowCore.Result> a,int end){
        try{int x=s.x.exclude(a,end);return validCombo(x)?x:recentRare(a,end,Math.min(12,end));}
        catch(Throwable t){return recentRare(a,end,Math.min(12,end));}
    }

    private static List<Strategy> strategies(int end){
        List<Strategy> s=new ArrayList<>();
        for(int w:new int[]{4,5,6,7,8,9,10,12,14,16,20,24,30,36,48,60}){final int q=Math.min(w,Math.max(4,end));s.add(new Strategy("Recent","최근희소-"+q,(a,e)->recentRare(a,e,q)));}
        for(int o:new int[]{1,2,3,4,5,6}){final int q=o;s.add(new Strategy("Markov","ComboMarkov-o"+o,(a,e)->markovRare(a,e,q)));}
        for(int w:new int[]{12,16,20,24,30,40,60}){final int q=Math.min(w,Math.max(12,end));s.add(new Strategy("Transition","전이희소-"+q,(a,e)->transitionRare(a,e,q)));}
        for(int n=3;n<=10;n++)for(int hd:new int[]{0,1,2}){final int q=n,h=hd;s.add(new Strategy("Shape","ShapeCombo-"+n+"-H"+hd,(a,e)->shapeRare(a,e,q,h)));}
        for(int n=3;n<=10;n++)for(int k:new int[]{3,5,7,9}){final int q=n,kk=k;s.add(new Strategy("KNN","ComboKNN-"+n+"-k"+k,(a,e)->knnRare(a,e,q,kk)));}
        for(int cap:new int[]{2,3,4,5,6}){final int q=cap;s.add(new Strategy("Run","RunCombo-"+cap,(a,e)->runRare(a,e,q)));}
        for(int lag:new int[]{1,2,3,4,5,6,7,8,9,10,11,12}){final int q=lag;s.add(new Strategy("Lag","LagCombo-"+lag,(a,e)->lagRare(a,e,q)));}
        for(int lb:new int[]{12,20,30,40,60}){final int q=Math.min(lb,Math.max(12,end));s.add(new Strategy("Meta","AUTORANGE-BestRecent-"+q,(a,e)->metaBestRecent(a,e,q)));}
        return s;
    }

    private static int metaBestRecent(List<FlowCore.Result>a,int end,int lookback){
        if(end<8)return bootstrapExclude(a,end);
        List<Strategy> core=metaCoreStrategies();
        int from=Math.max(4,end-Math.max(20,lookback));
        Strategy best=null;
        int bestMiss8=Integer.MAX_VALUE,bestMiss8Tie=Integer.MAX_VALUE,bestMissAll=Integer.MAX_VALUE,bestN=-1;
        for(Strategy st:core){
            int miss8=0,miss20=0,missAll=0,nAll=0;
            for(int t=from;t<end;t++){
                int ex=safeExclude(st,a,t);int actual=a.get(t).combo;if(!validCombo(actual))continue;
                nAll++;if(actual==ex)missAll++;
                if(t>=Math.max(from,end-20) && actual==ex)miss20++;
                if(t>=Math.max(from,end-8) && actual==ex)miss8++;
            }
            if(nAll==0)continue;
            boolean take=best==null || miss8<bestMiss8 || (miss8==bestMiss8 && miss20<bestMiss8Tie) || (miss8==bestMiss8 && miss20==bestMiss8Tie && missAll<bestMissAll) || (miss8==bestMiss8 && miss20==bestMiss8Tie && missAll==bestMissAll && nAll>bestN);
            if(take){best=st;bestMiss8=miss8;bestMiss8Tie=miss20;bestMissAll=missAll;bestN=nAll;}
        }
        return best==null?bootstrapExclude(a,end):safeExclude(best,a,end);
    }
    private static List<Strategy> metaCoreStrategies(){
        List<Strategy> s=new ArrayList<>();
        s.add(new Strategy("Recent","최근희소-8",(a,e)->recentRare(a,e,8)));
        s.add(new Strategy("Recent","최근희소-12",(a,e)->recentRare(a,e,12)));
        s.add(new Strategy("Recent","최근희소-20",(a,e)->recentRare(a,e,20)));
        s.add(new Strategy("Markov","ComboMarkov-o1",(a,e)->markovRare(a,e,1)));
        s.add(new Strategy("Markov","ComboMarkov-o2",(a,e)->markovRare(a,e,2)));
        s.add(new Strategy("Transition","전이희소-20",(a,e)->transitionRare(a,e,20)));
        s.add(new Strategy("Shape","Shape-5-H0",(a,e)->shapeRare(a,e,5,0)));
        s.add(new Strategy("Shape","Shape-7-H1",(a,e)->shapeRare(a,e,7,1)));
        s.add(new Strategy("KNN","KNN-5-k5",(a,e)->knnRare(a,e,5,5)));
        s.add(new Strategy("Run","Run-3",(a,e)->runRare(a,e,3)));
        s.add(new Strategy("Lag","Lag-2",(a,e)->lagRare(a,e,2)));
        return s;
    }

    private static int bootstrapExclude(List<FlowCore.Result>a,int end){
        int[] vote={0,0,0,0,0};
        for(int w:new int[]{4,6,8,10,12,16,20}){int ex=recentRare(a,end,Math.min(w,Math.max(1,end)));if(validCombo(ex))vote[ex]++;}
        int best=1,max=vote[1];for(int c=2;c<=4;c++)if(vote[c]>max){best=c;max=vote[c];}
        return best;
    }
    private static Eval bootstrapEval(List<FlowCore.Result>a,int end){
        Eval e=new Eval();int from=Math.max(2,end-20),split=from+Math.max(1,(end-from)/2);
        for(int t=from;t<end;t++){
            int ex=bootstrapExclude(a,t);int actual=a.get(t).combo;if(!validCombo(actual))continue;
            boolean ok=actual!=ex;e.n++;if(ok)e.h++;if(t<split){e.n1++;if(ok)e.h1++;}else{e.n2++;if(ok)e.h2++;}
        }
        e.excluded=bootstrapExclude(a,end);return e;
    }

    private static int recentRare(List<FlowCore.Result>a,int end,int window){
        if(end<=0)return 4;int[] c={0,0,0,0,0};int st=Math.max(0,end-Math.max(1,window));
        for(int i=st;i<end;i++)if(validCombo(a.get(i).combo))c[a.get(i).combo]++;
        return leastCount(c,lastCombo(a,end));
    }
    private static int markovRare(List<FlowCore.Result>a,int end,int order){
        if(end<=order)return recentRare(a,end,12);
        int[] c={0,0,0,0,0};int m=0;
        for(int next=order;next<end;next++){
            boolean ok=true;
            for(int q=0;q<order;q++)if(a.get(next-order+q).combo!=a.get(end-order+q).combo){ok=false;break;}
            if(ok){int v=a.get(next).combo;if(validCombo(v)){c[v]++;m++;}}
        }
        if(m<2)return order>1?markovRare(a,end,order-1):transitionRare(a,end,40);
        return leastCount(c,lastCombo(a,end));
    }
    private static int transitionRare(List<FlowCore.Result>a,int end,int window){
        if(end<2)return recentRare(a,end,12);int last=a.get(end-1).combo;int[] c={0,0,0,0,0};int m=0,st=Math.max(1,end-window);
        for(int next=st;next<end;next++)if(a.get(next-1).combo==last){int v=a.get(next).combo;if(validCombo(v)){c[v]++;m++;}}
        if(m<2)return recentRare(a,end,window);
        return leastCount(c,last);
    }
    private static int shapeRare(List<FlowCore.Result>a,int end,int len,int maxHd){
        if(end<=len+1)return recentRare(a,end,12);
        int[] cur=transitionBits(a,end-len,end);int[] c={0,0,0,0,0};int m=0;
        for(int next=len;next<end;next++){
            int[] old=transitionBits(a,next-len,next);int hd=hammingBits(cur,old);
            if(hd<=maxHd){int v=a.get(next).combo;if(validCombo(v)){c[v]++;m++;}}
        }
        if(m<2)return markovRare(a,end,Math.min(2,Math.max(1,len-1)));
        return leastCount(c,lastCombo(a,end));
    }
    private static int[] transitionBits(List<FlowCore.Result>a,int from,int to){
        int steps=Math.max(0,to-from-1);int[] out=new int[steps*3];int z=0;
        for(int i=from+1;i<to;i++){int p=a.get(i-1).combo,n=a.get(i).combo;for(int d=0;d<3;d++)out[z++]=vec(p,d)==vec(n,d)?0:1;}
        return out;
    }
    private static int hammingBits(int[]a,int[]b){int n=Math.abs(a.length-b.length),m=Math.min(a.length,b.length);for(int i=0;i<m;i++)if(a[i]!=b[i])n++;return n;}
    private static int knnRare(List<FlowCore.Result>a,int end,int len,int k){
        if(end<=len+1)return recentRare(a,end,12);
        List<int[]> rows=new ArrayList<>();
        for(int next=len;next<end;next++){
            int dist=0;for(int q=0;q<len;q++)if(a.get(next-len+q).combo!=a.get(end-len+q).combo)dist++;
            rows.add(new int[]{dist,a.get(next).combo});
        }
        if(rows.isEmpty())return recentRare(a,end,12);
        rows.sort(Comparator.comparingInt(x->x[0]));int[] c={0,0,0,0,0};int n=Math.min(k,rows.size());
        for(int i=0;i<n;i++){int v=rows.get(i)[1];if(validCombo(v))c[v]++;}
        return leastCount(c,lastCombo(a,end));
    }
    private static int runRare(List<FlowCore.Result>a,int end,int cap){
        if(end<3)return recentRare(a,end,12);int last=lastCombo(a,end),run=1;
        for(int i=end-2;i>=0&&run<cap&&a.get(i).combo==last;i--)run++;
        int[] c={0,0,0,0,0};int m=0;
        for(int next=1;next<end;next++){
            if(a.get(next-1).combo!=last)continue;int rr=1;
            for(int i=next-2;i>=0&&rr<cap&&a.get(i).combo==last;i--)rr++;
            if(rr==run){int v=a.get(next).combo;if(validCombo(v)){c[v]++;m++;}}
        }
        if(m<2)return transitionRare(a,end,40);
        return leastCount(c,last);
    }
    private static int lagRare(List<FlowCore.Result>a,int end,int lag){
        if(end<=lag+2)return recentRare(a,end,20);int key=a.get(end-lag).combo;int[] c={0,0,0,0,0};int m=0;
        for(int next=lag;next<end;next++)if(a.get(next-lag).combo==key){int v=a.get(next).combo;if(validCombo(v)){c[v]++;m++;}}
        if(m<2)return recentRare(a,end,20);
        return leastCount(c,lastCombo(a,end));
    }
    private static int leastCount(int[] c,int avoidTie){
        int min=Integer.MAX_VALUE;for(int x=1;x<=4;x++)min=Math.min(min,c[x]);
        for(int x=1;x<=4;x++)if(c[x]==min&&x!=avoidTie)return x;
        for(int x=1;x<=4;x++)if(c[x]==min)return x;
        return 4;
    }
    private static int lastCombo(List<FlowCore.Result>a,int end){return end>0&&validCombo(a.get(end-1).combo)?a.get(end-1).combo:4;}
    private static boolean validCombo(int c){return c>=1&&c<=4;}
    private static String combo(int c){return validCombo(c)?FlowCore.COMBO[c]:"-";}
    private static String includeLabel(int excluded){StringBuilder sb=new StringBuilder();for(int c=1;c<=4;c++)if(c!=excluded){if(sb.length()>0)sb.append(" · ");sb.append(FlowCore.COMBO[c]);}return sb.toString();}
    private static int vec(int combo,int dim){switch(combo){case 1:return dim==0?+1:dim==1?+1:-1;case 2:return dim==0?+1:dim==1?-1:+1;case 3:return dim==0?-1:dim==1?+1:+1;case 4:return -1;default:return 0;}}
    private static String pct(double v){return String.format(Locale.KOREA,"%.1f%%",v*100.0);}
}
