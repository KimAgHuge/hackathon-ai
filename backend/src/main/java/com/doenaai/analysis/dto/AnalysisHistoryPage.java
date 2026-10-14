package com.doenaai.analysis.dto;

import java.util.List;

public record AnalysisHistoryPage(List<AnalysisHistoryItem> items, long total, int page, int size) {}
