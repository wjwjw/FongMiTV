package com.fongmi.android.tv.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.AdapterRecommendBinding;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RecommendAdapter extends RecyclerView.Adapter<RecommendAdapter.ViewHolder> {

    private final OnClickListener listener;
    private List<Config> items;
    private String currentUrl = "";
    private final Map<String, String> speeds = new HashMap<>();

    public RecommendAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {
        void onClick(Config item);
    }

    /** 更新指定源的测速结果并刷新对应条目。 */
    public void setSpeed(String url, String text) {
        speeds.put(normalize(url), text);
        int idx = indexOf(url);
        if (idx >= 0) notifyItemChanged(idx);
    }

    private int indexOf(String url) {
        if (items == null) return -1;
        String target = normalize(url);
        for (int i = 0; i < items.size(); i++) {
            if (normalize(items.get(i).getUrl()).equals(target)) return i;
        }
        return -1;
    }

    /** 设置当前正在使用的源 URL（normalized），匹配项在名称前加「✓ 」标记。 */
    public RecommendAdapter setCurrentUrl(String url) {
        this.currentUrl = url == null ? "" : url;
        notifyDataSetChanged();
        return this;
    }

    private static String normalize(String url) {
        if (url == null) return "";
        return url.trim().toLowerCase().replace("raw.githubusercontent.com/qist/tvbox/master/", "qist.wyfc.qzz.io/");
    }

    /** 按延迟分档着色：失败/解析失败=红；≤400ms=绿；≤1200ms=黄；更慢=橙。 */
    private static int speedColor(Context context, String speed) {
        if (speed.equals(context.getString(R.string.recommend_speed_fail))) return 0xFFEF5350;
        try {
            int ms = Integer.parseInt(speed.replaceAll("\\D.*", ""));
            if (ms <= 400) return 0xFF66BB6A;
            if (ms <= 1200) return 0xFFFBC02D;
            return 0xFFFFA726;
        } catch (NumberFormatException e) {
            return 0xFFEF5350;
        }
    }

    public RecommendAdapter setItems(List<Config> items) {
        this.items = items;
        notifyDataSetChanged();
        return this;
    }

    @Override
    public int getItemCount() {
        return items == null ? 0 : items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterRecommendBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Config item = items.get(position);
        boolean current = !currentUrl.isEmpty() && normalize(item.getUrl()).equals(currentUrl);
        holder.binding.name.setText((current ? "✓ " : "") + item.getName());
        holder.binding.url.setText(item.getUrl());
        String speed = speeds.get(normalize(item.getUrl()));
        holder.binding.speed.setVisibility(speed == null ? View.GONE : View.VISIBLE);
        if (speed != null) {
            holder.binding.speed.setText(speed);
            holder.binding.speed.setTextColor(speedColor(holder.binding.getRoot().getContext(), speed));
        }
        holder.binding.getRoot().setOnClickListener(v -> listener.onClick(item));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterRecommendBinding binding;

        public ViewHolder(@NonNull AdapterRecommendBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
