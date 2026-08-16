# 보글사다리 DUAL-RANGE FINAL1 V1.10

V1.7 + V1.9를 1차에서 동시에 분석하고, 합성 규칙으로 4개 중 1개를 제외한 뒤 남은 3개에서 최종 1픽 하나만 표시하는 앱입니다.

## 구조
- 1차 V1.7 + V1.9 동시분석
- 두 엔진 제외가 같으면 그대로 합의
- 다르면 V1.9 선택 학습길이 36회 이상일 때 V1.9, 미만이면 V1.7
- 2차 FinalOnePickEngine이 남은 3개 중 최종 1픽 하나 선택
- 화면에는 최종 1픽 1개만 크게 표시
- 1차/2차 walk-forward 검증은 상세영역에 따로 표시
- 자동조회 / 자동채점 / 백업 / 복원 / 리셋

## GitHub Codespaces
```bash
bash 보글사다리_DUAL_RANGE_FINAL1_V1.10_원클릭.sh
```

Actions > Build Android APK > Artifacts > `BubbleDualRangeFinal1V110-debug-apk`

패키지: `com.bubbleladder.dualrangefinal1v110`
