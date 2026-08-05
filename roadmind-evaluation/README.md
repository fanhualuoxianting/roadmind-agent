# RoadMind 离线评测

这是可重复运行的 `RULE_STUB` 评测，不代表真实模型质量、真实地图覆盖率或生产延迟。用例和观测结果均在 [`cases.json`](cases.json) 中版本化，报告会保存运行模式、模型标识、种子、温度、用例 SHA-256 和失败明细。

```powershell
python .\roadmind-evaluation\run_evaluation.py
python -m unittest discover -s .\roadmind-evaluation -p 'test_*.py'
```

报告默认写入 `roadmind-evaluation/reports/latest.json`，不纳入 Git；公开报告中的百分比必须从该 JSON 的 `cases` 和 `metrics` 重新计算。
