package com.example.myapplication;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    public interface OnClick {
        void onClick(ScanItem item);
    }

    private final List<ScanItem> items;
    private final OnClick onClick;

    public HistoryAdapter(List<ScanItem> items, OnClick onClick) {
        this.items = items;
        this.onClick = onClick;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history_thumb, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        ScanItem item = items.get(position);

        Bitmap bmp = ScanStorage.decodeSampled(item.imagePath, 300, 300);
        holder.iv.setImageBitmap(bmp);

        holder.itemView.setOnClickListener(v -> onClick.onClick(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView iv;
        VH(@NonNull View itemView) {
            super(itemView);
            iv = itemView.findViewById(R.id.ivThumb);
        }
    }
}