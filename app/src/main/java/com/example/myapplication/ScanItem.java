package com.example.myapplication;

public class ScanItem {
    public final String imagePath;
    public final String disease;
    public final int confidencePercent;
    public final String recommendations;
    public final long timestamp;

    public ScanItem(String imagePath, String disease, int confidencePercent, String recommendations, long timestamp) {
        this.imagePath = imagePath;
        this.disease = disease;
        this.confidencePercent = confidencePercent;
        this.recommendations = recommendations;
        this.timestamp = timestamp;
    }
}