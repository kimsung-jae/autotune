package com.bubbleladder.mhsunweightedv1;

import java.util.*;

/**
 * V1.10 DUAL-RANGE stage-1 engine.
 * Runs V1.7 and V1.9 independently on the same prefix, then combines them
 * without looking at the next outcome.
 *
 * Rule validated in prior 30-round comparison:
 * - Same excluded combo -> agreement, keep it.
 * - Disagreement -> if V1.9 selected learning window >= 36, use V1.9.
 * - Otherwise use V1.7.
 */
public final class TriPick80Engine {
    private TriPick80Engine(){}
    public static final double TARGET=0.80;

    public static final class Result {
        public boolean certified;
        public boolean targetAchieved;
        public int excludedCombo=0;
        public int validationN=0, validationHit=0, preN=0, preHit=0, holdN=0, holdHit=0, searched=0;
        public double validationRate=0.0, preRate=0.0, holdRate=0.0, firstRate=0.0, secondRate=0.0;
        public double bestObservedRate=0.0;
        public int v19SelectedWindow=0;
        public int v17Excluded=0, v19Excluded=0;
        public String source="-";
        public String mode="-", rule="-", detail="-", picksLabel="-", excludedLabel="-";
    }

    public static Result optimize(List<FlowCore.Result> all){
        Result out=new Result();
        if(all==null||all.isEmpty()){out.detail="DUAL-RANGE 데이터 준비중";return out;}
        TriPick80EngineV17.Result a=null;
        AutoRange80EngineV19.Result b=null;
        try{a=TriPick80EngineV17.optimize(all);}catch(Throwable ignored){}
        try{b=AutoRange80EngineV19.optimize(all);}catch(Throwable ignored){}
        if(a==null&&b==null){out.detail="V1.7/V1.9 동시 분석 실패";return out;}
        if(a==null)return fromV19(b,"V1.9 단독 보호모드");
        if(b==null)return fromV17(a,"V1.7 단독 보호모드");

        out.v17Excluded=a.excludedCombo;
        out.v19Excluded=b.excludedCombo;
        out.v19SelectedWindow=b.selectedWindow;
        boolean validA=valid(a.excludedCombo), validB=valid(b.excludedCombo);
        if(!validA&&validB)return fromV19(b,"V1.9 선택 · V1.7 무효");
        if(validA&&!validB)return fromV17(a,"V1.7 선택 · V1.9 무효");
        if(!validA&&!validB){out.detail="두 엔진 제외픽 준비중";return out;}

        if(a.excludedCombo==b.excludedCombo){
            // 동일 제외픽이면 더 큰 검증표본/검증률 쪽 수치를 표시하되, 결과는 합의값 그대로.
            Result r=(b.validationN>a.validationN || (b.validationN==a.validationN && b.validationRate>a.validationRate))
                    ?fromV19(b,"DUAL 합의") : fromV17(a,"DUAL 합의");
            r.v17Excluded=a.excludedCombo;r.v19Excluded=b.excludedCombo;r.v19SelectedWindow=b.selectedWindow;
            r.source="V1.7 + V1.9 합의";
            r.mode="🤝 DUAL-RANGE 합의";
            r.rule="V1.7/V1.9 동일 제외 → 그대로 사용";
            r.detail="V1.7 제외 "+label(a.excludedCombo)+" · V1.9 제외 "+label(b.excludedCombo)+
                    " · V1.9 선택학습 "+b.selectedWindow+"회 · 합의 제외 "+label(r.excludedCombo)+
                    "\n"+r.detail;
            return r;
        }

        boolean choose19=b.selectedWindow>=36;
        Result r=choose19?fromV19(b,"DUAL 불일치 → V1.9"):fromV17(a,"DUAL 불일치 → V1.7");
        r.v17Excluded=a.excludedCombo;r.v19Excluded=b.excludedCombo;r.v19SelectedWindow=b.selectedWindow;
        r.source=choose19?"V1.9":"V1.7";
        r.mode="⚖ DUAL-RANGE 불일치 자동결정";
        r.rule=choose19?"불일치 + V1.9 학습길이 ≥36 → V1.9":"불일치 + V1.9 학습길이 <36 → V1.7";
        r.detail="V1.7 제외 "+label(a.excludedCombo)+" · V1.9 제외 "+label(b.excludedCombo)+
                " · V1.9 선택학습 "+b.selectedWindow+"회 · 최종 제외 "+label(r.excludedCombo)+
                " · 선택엔진 "+r.source+"\n"+r.detail;
        return r;
    }

    private static Result fromV17(TriPick80EngineV17.Result x,String why){
        Result r=new Result();
        if(x==null)return r;
        r.certified=x.certified;r.targetAchieved=x.targetAchieved;r.excludedCombo=x.excludedCombo;
        r.validationN=x.validationN;r.validationHit=x.validationHit;r.preN=x.preN;r.preHit=x.preHit;
        r.holdN=x.holdN;r.holdHit=x.holdHit;r.searched=x.searched;r.validationRate=x.validationRate;
        r.preRate=x.preRate;r.holdRate=x.holdRate;r.firstRate=x.firstRate;r.secondRate=x.secondRate;
        r.bestObservedRate=x.bestObservedRate;r.mode=why;r.rule=x.rule;r.detail=x.detail;
        r.picksLabel=x.picksLabel;r.excludedLabel=x.excludedLabel;r.source="V1.7";
        return r;
    }
    private static Result fromV19(AutoRange80EngineV19.Result x,String why){
        Result r=new Result();
        if(x==null)return r;
        r.certified=x.certified;r.targetAchieved=x.targetAchieved;r.excludedCombo=x.excludedCombo;
        r.validationN=x.validationN;r.validationHit=x.validationHit;r.preN=x.preN;r.preHit=x.preHit;
        r.holdN=x.holdN;r.holdHit=x.holdHit;r.searched=x.searched;r.validationRate=x.validationRate;
        r.preRate=x.preRate;r.holdRate=x.holdRate;r.firstRate=x.firstRate;r.secondRate=x.secondRate;
        r.bestObservedRate=x.bestObservedRate;r.mode=why;r.rule=x.rule;r.detail=x.detail;
        r.picksLabel=x.picksLabel;r.excludedLabel=x.excludedLabel;r.v19SelectedWindow=x.selectedWindow;r.source="V1.9";
        return r;
    }

    /**
     * Lightweight future-blind historical filter used only for the 2nd-stage walk-forward display.
     * Current live recommendation still uses the full V1.7 + full V1.9 optimize() above.
     */
    public static int fastHistoricalExclude(List<FlowCore.Result> all){
        if(all==null||all.isEmpty())return 4;
        int ex17=0;
        try{TriPick80EngineV17.Result a=TriPick80EngineV17.optimize(all);ex17=a==null?0:a.excludedCombo;}catch(Throwable ignored){}
        FastV19 q=fastV19(all);
        if(!valid(ex17))return valid(q.excluded)?q.excluded:4;
        if(!valid(q.excluded))return ex17;
        if(ex17==q.excluded)return ex17;
        return q.window>=36?q.excluded:ex17;
    }

    private static final class FastV19 { int excluded,window; double score; }
    private static FastV19 fastV19(List<FlowCore.Result> all){
        int n=all.size(); FastV19 best=null;
        LinkedHashSet<Integer> ws=new LinkedHashSet<>();
        for(int w=12;w<=Math.min(n,30);w+=2)ws.add(w);
        for(int w:new int[]{36,40,50,60,80,100,120,150})if(w<=n)ws.add(w);
        ws.add(n);
        for(int w:ws){
            if(w<4||w>n)continue;
            int from=n-w;
            int[] c=new int[5];
            for(int i=from;i<n;i++){int v=all.get(i).combo;if(valid(v))c[v]++;}
            int ex=1;for(int x=2;x<=4;x++)if(c[x]<c[ex])ex=x;
            int btStart=Math.max(from+4,n-Math.min(20,w-1)); int hit=0,total=0;
            for(int t=btStart;t<n;t++){
                int[] pc=new int[5];int pst=Math.max(from,t-w);
                for(int i=pst;i<t;i++){int v=all.get(i).combo;if(valid(v))pc[v]++;}
                int pe=1;for(int x=2;x<=4;x++)if(pc[x]<pc[pe])pe=x;
                int actual=all.get(t).combo;if(valid(actual)){total++;if(actual!=pe)hit++;}
            }
            double rate=total==0?0:(double)hit/total;
            // stability preference: rate first, then larger sample/window when close.
            double score=rate + Math.min(0.03,total/1000.0) + (w>=24?0.002:0.0);
            if(best==null||score>best.score+1e-12||(Math.abs(score-best.score)<1e-12&&w>best.window)){
                best=new FastV19();best.excluded=ex;best.window=w;best.score=score;
            }
        }
        if(best==null){best=new FastV19();best.excluded=4;best.window=n;}
        return best;
    }
    private static boolean valid(int c){return c>=1&&c<=4;}
    private static String label(int c){return valid(c)?FlowCore.COMBO[c]:"-";}
}
