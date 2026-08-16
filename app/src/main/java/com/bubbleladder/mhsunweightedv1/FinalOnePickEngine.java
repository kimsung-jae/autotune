package com.bubbleladder.mhsunweightedv1;

import java.util.*;

/**
 * 2단계 최종 1픽 엔진.
 * 1차 TriPick80Engine이 제외한 조합은 후보에서 완전히 제거하고,
 * 남은 3개 중 여러 독립 규칙의 동등 1표 투표로 최종 1개만 선택한다.
 *
 * 표시되는 validationRate는 마지막 최대 30회에 대해 각 시점의 미래 결과를 가리고
 * 1차 제외값부터 다시 계산한 뒤 2차 1픽까지 재현한 walk-forward 적중률이다.
 */
public final class FinalOnePickEngine {
    private FinalOnePickEngine(){}
    private static final int WARMUP=12;
    private static final int MAX_BT=30;

    public static final class Result {
        public int excludedCombo=0;
        public int pickCombo=0;
        public int votes=0,totalVotes=0;
        public int validationN=0,validationHit=0;
        public double voteShare=0.0,validationRate=0.0;
        public String pickLabel="-",excludedLabel="-",rule="-",detail="-";
    }

    public static Result optimize(List<FlowCore.Result> all,int currentExcluded){
        Result r=new Result();
        if(all==null||all.isEmpty()){
            r.detail="2차 최종 1픽 데이터 준비중";
            return r;
        }
        int end=all.size();
        int ex=valid(currentExcluded)?currentExcluded:fallbackExclude(all,end);
        Pick p=choose(all,end,ex);
        r.excludedCombo=ex;
        r.pickCombo=p.combo;
        r.votes=p.votes;
        r.totalVotes=p.total;
        r.voteShare=p.total==0?0.0:(double)p.votes/p.total;
        r.pickLabel=label(p.combo);
        r.excludedLabel=label(ex);
        r.rule="남은3개 동등투표 · Recent/Markov/ExactShape/KNN";

        // 최대 30회 전체 파이프라인 walk-forward: 각 시점 이전 데이터만 사용.
        int from=Math.max(WARMUP,end-MAX_BT);
        for(int t=from;t<end;t++){
            List<FlowCore.Result> prefix=new ArrayList<>(all.subList(0,t));
            if(prefix.isEmpty())continue;
            int pastEx;
            try{
                pastEx=TriPick80Engine.fastHistoricalExclude(prefix);
                if(!valid(pastEx))pastEx=fallbackExclude(all,t);
            }catch(Throwable e){
                pastEx=fallbackExclude(all,t);
            }
            Pick q=choose(all,t,pastEx);
            if(!valid(q.combo))continue;
            r.validationN++;
            if(all.get(t).combo==q.combo)r.validationHit++;
        }
        r.validationRate=r.validationN==0?0.0:(double)r.validationHit/r.validationN;
        r.detail="1차 제외 "+r.excludedLabel+" → 남은 3개 중 최종 "+r.pickLabel+
                " · 동등투표 "+r.votes+"/"+r.totalVotes+
                " · 2단계 경량 DUAL walk-forward "+r.validationHit+"/"+r.validationN+" = "+pct(r.validationRate);
        return r;
    }

    private static final class Pick { int combo,votes,total; }

    private static Pick choose(List<FlowCore.Result>a,int end,int excluded){
        Pick out=new Pick();
        if(end<=0){out.combo=firstAllowed(excluded);return out;}
        int[] vote=new int[5]; int total=0;

        // 최근 빈도 5개 창: 각 창은 딱 1표.
        for(int w:new int[]{6,10,20,30,50}){
            int x=recentWinner(a,end,w,excluded);
            if(valid(x)){vote[x]++;total++;}
        }
        // 조합 Markov 1~3차: 표본이 있을 때만 1표.
        for(int order:new int[]{1,2,3}){
            int x=markovWinner(a,end,order,excluded);
            if(valid(x)){vote[x]++;total++;}
        }
        // 완전 동일 최근모양 2~6칸: 동일 패턴 뒤 결과가 존재할 때만 1표.
        for(int len:new int[]{2,3,4,5,6}){
            int x=shapeWinner(a,end,len,excluded);
            if(valid(x)){vote[x]++;total++;}
        }
        // 유사구간 최근모양 4/6/8칸: 가장 가까운 과거 5개 후속값에서 1표.
        for(int len:new int[]{4,6,8}){
            int x=knnWinner(a,end,len,5,excluded);
            if(valid(x)){vote[x]++;total++;}
        }

        int best=0,bv=-1;
        for(int c=1;c<=4;c++)if(c!=excluded){
            if(vote[c]>bv){best=c;bv=vote[c];}
            else if(vote[c]==bv && tieScore(a,end,c)>tieScore(a,end,best))best=c;
        }
        if(!valid(best)||best==excluded)best=firstAllowed(excluded);
        out.combo=best;out.votes=Math.max(0,vote[best]);out.total=total;
        return out;
    }

    private static int recentWinner(List<FlowCore.Result>a,int end,int window,int excluded){
        int[] c=new int[5];int st=Math.max(0,end-window);
        for(int i=st;i<end;i++){int v=a.get(i).combo;if(valid(v)&&v!=excluded)c[v]++;}
        return maxCount(c,excluded,1);
    }

    private static int markovWinner(List<FlowCore.Result>a,int end,int order,int excluded){
        if(end<=order)return 0;int[] c=new int[5];int m=0;
        for(int next=order;next<end;next++){
            boolean ok=true;
            for(int q=0;q<order;q++)if(a.get(next-order+q).combo!=a.get(end-order+q).combo){ok=false;break;}
            if(ok){int v=a.get(next).combo;if(valid(v)&&v!=excluded){c[v]++;m++;}}
        }
        return m==0?0:maxCount(c,excluded,1);
    }

    private static int shapeWinner(List<FlowCore.Result>a,int end,int len,int excluded){
        if(end<=len)return 0;int[] c=new int[5];int m=0;
        for(int next=len;next<end;next++){
            boolean ok=true;
            for(int q=0;q<len;q++)if(a.get(next-len+q).combo!=a.get(end-len+q).combo){ok=false;break;}
            if(ok){int v=a.get(next).combo;if(valid(v)&&v!=excluded){c[v]++;m++;}}
        }
        return m==0?0:maxCount(c,excluded,1);
    }

    private static int knnWinner(List<FlowCore.Result>a,int end,int len,int k,int excluded){
        if(end<=len+1)return 0;
        List<int[]> rows=new ArrayList<>();
        for(int next=len;next<end;next++){
            int d=0;for(int q=0;q<len;q++)if(a.get(next-len+q).combo!=a.get(end-len+q).combo)d++;
            int v=a.get(next).combo;if(valid(v)&&v!=excluded)rows.add(new int[]{d,v,next});
        }
        if(rows.isEmpty())return 0;
        rows.sort((x,y)->x[0]!=y[0]?Integer.compare(x[0],y[0]):Integer.compare(y[2],x[2]));
        int[] c=new int[5];int n=Math.min(k,rows.size());for(int i=0;i<n;i++)c[rows.get(i)[1]]++;
        return maxCount(c,excluded,1);
    }

    private static int maxCount(int[]c,int excluded,int minSample){
        int best=0,max=-1,total=0;
        for(int x=1;x<=4;x++)if(x!=excluded){total+=c[x];if(c[x]>max){max=c[x];best=x;}}
        if(total<minSample)return 0;
        // 완전 동률이면 기권해서 우연한 숫자순 편향을 줄인다.
        int ties=0;for(int x=1;x<=4;x++)if(x!=excluded&&c[x]==max)ties++;
        return ties==1?best:0;
    }

    private static int tieScore(List<FlowCore.Result>a,int end,int combo){
        if(!valid(combo))return Integer.MIN_VALUE;
        int s=0;int st=Math.max(0,end-20);
        for(int i=st;i<end;i++)if(a.get(i).combo==combo)s+=10;
        // 최근에 나온 동일 조합은 작은 보조점수. 확률이라고 표시하지 않는다.
        for(int i=end-1,age=0;i>=Math.max(0,end-8);i--,age++)if(a.get(i).combo==combo){s+=8-age;break;}
        return s;
    }

    private static int fallbackExclude(List<FlowCore.Result>a,int end){
        int[] c=new int[5];int st=Math.max(0,end-20);
        for(int i=st;i<end;i++){int v=a.get(i).combo;if(valid(v))c[v]++;}
        int ex=1,min=c[1];for(int x=2;x<=4;x++)if(c[x]<min){min=c[x];ex=x;}
        return ex;
    }
    private static int firstAllowed(int ex){for(int c=1;c<=4;c++)if(c!=ex)return c;return 1;}
    private static boolean valid(int c){return c>=1&&c<=4;}
    private static String label(int c){return valid(c)?FlowCore.COMBO[c]:"-";}
    private static String pct(double v){return String.format(Locale.KOREA,"%.1f%%",v*100.0);}
}
