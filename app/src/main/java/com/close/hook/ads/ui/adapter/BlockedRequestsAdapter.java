package com.close.hook.ads.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.close.hook.ads.R;
import com.close.hook.ads.data.module.BlockedRequest;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class BlockedRequestsAdapter extends RecyclerView.Adapter<BlockedRequestsAdapter.ViewHolder> {
    private Context context;
    private List<BlockedRequest> blockedRequests;

    public BlockedRequestsAdapter(Context context, List<BlockedRequest> blockedRequests) {
        this.context = context;
        this.blockedRequests = blockedRequests;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.blocked_request_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        BlockedRequest request = blockedRequests.get(position);
        holder.appName.setText(request.appName);
        holder.request.setText(request.request);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        holder.timestamp.setText(sdf.format(new Date(request.timestamp)));
    }

    @Override
    public int getItemCount() {
        return blockedRequests.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public TextView appName;
        public TextView request;
        public TextView timestamp;

        public ViewHolder(View view) {
            super(view);
            appName = view.findViewById(R.id.app_name);
            request = view.findViewById(R.id.request);
            timestamp = view.findViewById(R.id.timestamp);
        }
    }
}
