package com.example.myapplication;

public class DiagnosisResult {
    public final String disease;
    public final int confidencePercent;
    public final String recommendations;
    public DiagnosisResult(String disease, int confidencePercent, String recommendations) {
        this.disease = disease;
        this.confidencePercent = confidencePercent;
        this.recommendations = recommendations;
    }
    public static DiagnosisResult fallback(String msg) {
        return new DiagnosisResult("Не удалось определить", 0, msg);
    }
}
